package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.openphc.cce.compliance.domain.entity.EventLog;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.ProcessingStatus;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.CloudEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central orchestrator through which all inbound event processing flows.
 * Wires together idempotency, resource extraction, two-tier matching,
 * enrollment, step completion, progressive instantiation, and deviation recording.
 */
@Service
@Transactional
public class ComplianceEngine {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEngine.class);

    private final EventLogService eventLogService;
    private final ResourceInfoExtractor resourceInfoExtractor;
    private final TriggerMatchingService triggerMatchingService;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ProtocolDefinitionService protocolDefinitionService;
    private final ProtocolInstanceService protocolInstanceService;
    private final StepInstanceService stepInstanceService;
    private final PlanDefinitionParser planDefinitionParser;
    private final IntelligenceRuleService intelligenceRuleService;
    private final AuditService auditService;

    private final Counter eventsProcessedCounter;
    private final Counter eventsMatchedCounter;
    private final Counter eventsDuplicateCounter;
    private final Counter eventsZeroMatchCounter;
    private final Timer matchingDurationTimer;
    private final Timer eventProcessingTimer;

    public ComplianceEngine(EventLogService eventLogService,
                            ResourceInfoExtractor resourceInfoExtractor,
                            TriggerMatchingService triggerMatchingService,
                            ExpressionEvaluationService expressionEvaluationService,
                            ProtocolDefinitionService protocolDefinitionService,
                            ProtocolInstanceService protocolInstanceService,
                            StepInstanceService stepInstanceService,
                            PlanDefinitionParser planDefinitionParser,
                            IntelligenceRuleService intelligenceRuleService,
                            AuditService auditService,
                            MeterRegistry meterRegistry) {
        this.eventLogService = eventLogService;
        this.resourceInfoExtractor = resourceInfoExtractor;
        this.triggerMatchingService = triggerMatchingService;
        this.expressionEvaluationService = expressionEvaluationService;
        this.protocolDefinitionService = protocolDefinitionService;
        this.protocolInstanceService = protocolInstanceService;
        this.stepInstanceService = stepInstanceService;
        this.planDefinitionParser = planDefinitionParser;
        this.intelligenceRuleService = intelligenceRuleService;
        this.auditService = auditService;

        this.eventsProcessedCounter = meterRegistry.counter("cce.events.processed");
        this.eventsMatchedCounter = Counter.builder("cce.events.matched")
                .tag("status", "matched").register(meterRegistry);
        this.eventsDuplicateCounter = meterRegistry.counter("cce.events.duplicate");
        this.eventsZeroMatchCounter = Counter.builder("cce.events.matched")
                .tag("status", "zero_match").register(meterRegistry);
        this.matchingDurationTimer = meterRegistry.timer("cce.step.matching.duration");
        this.eventProcessingTimer = meterRegistry.timer("cce.events.processing.duration");
    }

    /**
     * Main entry point for all inbound clinical events.
     */
    public void processInboundEvent(CloudEventMessage event) {
        eventProcessingTimer.record(() -> doProcessInboundEvent(event));
    }

    private void doProcessInboundEvent(CloudEventMessage event) {
        eventsProcessedCounter.increment();

        // Step 1: Idempotency check
        if (eventLogService.isDuplicate(event.getId(), event.getSource())) {
            log.info("Duplicate event detected: cloudeventsId={}, source={}", event.getId(), event.getSource());
            eventLogService.recordEvent(event, ProcessingStatus.DUPLICATE);
            eventsDuplicateCounter.increment();
            return;
        }

        // Step 2: Record event with initial ZERO_MATCH status
        EventLog eventLog = eventLogService.recordEvent(event, ProcessingStatus.ZERO_MATCH);

        // Step 3: Extract resource info from payload
        JsonNode data = event.getData();
        String resourceType = resourceInfoExtractor.extractResourceType(data);
        List<CodePathTriple> codes = resourceInfoExtractor.extractCodes(data);

        // Step 4: Check for explicit match
        if (event.getActionid() != null && !event.getActionid().isBlank()) {
            processExplicitMatch(event, eventLog);
            return;
        }

        // Steps 5-7: Two-tier matching
        Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache = new HashMap<>();
        List<MatchedAction> finalMatches = matchingDurationTimer.record(() ->
                performTwoTierMatching(resourceType, codes, data, actionCache));

        // Step 8: Result classification
        if (finalMatches != null && !finalMatches.isEmpty()) {
            for (MatchedAction match : finalMatches) {
                processMatch(match, event, eventLog, actionCache);
            }
            eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
            eventsMatchedCounter.increment();

            log.info("Event processing complete: cloudeventsId={}, matches={}",
                    event.getId(), finalMatches.size());
        } else {
            eventsZeroMatchCounter.increment();
            log.info("No matches for event: cloudeventsId={}, resourceType={}",
                    event.getId(), resourceType);
        }
    }

    /**
     * Process an explicit match — bypass Tier 1/2 entirely.
     * Uses actionId and protocolInstanceId from CloudEvent extensions.
     */
    void processExplicitMatch(CloudEventMessage event, EventLog eventLog) {
        String actionId = event.getActionid();
        String protocolInstanceIdStr = event.getProtocolinstanceid();

        if (protocolInstanceIdStr == null || protocolInstanceIdStr.isBlank()) {
            log.warn("Explicit match requested but protocolInstanceId is missing: cloudeventsId={}",
                    event.getId());
            return;
        }

        UUID protocolInstanceId = UUID.fromString(protocolInstanceIdStr);
        ProtocolInstance protocolInstance = protocolInstanceService.findById(protocolInstanceId);
        Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache = new HashMap<>();

        StepInstance step = stepInstanceService.findActionableStep(protocolInstanceId, actionId);
        if (step == null) {
            step = createInitialStep(protocolInstance, actionId, actionCache);
        }

        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource());

        // Evaluate intelligence rules on step completion
        intelligenceRuleService.evaluateOnCompletion(step, protocolInstance);

        eventLogService.updateStatus(eventLog, ProcessingStatus.MATCHED);
        eventsMatchedCounter.increment();

        log.info("Explicit match processed: cloudeventsId={}, actionId={}, protocolInstanceId={}",
                event.getId(), actionId, protocolInstanceId);
    }

    private List<MatchedAction> performTwoTierMatching(String resourceType, List<CodePathTriple> codes,
                                                       JsonNode eventData,
                                                       Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        List<MatchedAction> finalMatches = new ArrayList<>();

        // Step 5: Tier 1 structural match
        List<MatchedAction> tier1Matches = triggerMatchingService.findStructuralMatches(resourceType, codes);

        // Step 6: Collect condition-only triggers
        List<ConditionOnlyTrigger> conditionOnlyTriggers = triggerMatchingService.getConditionOnlyTriggers();

        // Step 7: Tier 2 condition evaluation

        // Evaluate Tier 1 results — check if they have conditions
        for (MatchedAction match : tier1Matches) {
            var actions = getActionsForProtocol(match.protocolDefinitionId(), actionCache);

            var actionMetadata = actions.stream()
                    .filter(a -> match.actionId().equals(a.id()))
                    .findFirst()
                    .orElse(null);

            if (actionMetadata == null) {
                continue;
            }

            // Check if this action's trigger has a condition
            boolean hasCondition = actionMetadata.triggers().stream()
                    .anyMatch(t -> t.condition() != null);

            if (!hasCondition) {
                // Scenario 1 (F1) or Scenario 2 (F1,F2) — no condition, pass directly
                finalMatches.add(match);
            } else {
                // Scenario 3 (F1,F3) or Scenario 4 (F1,F2,F3) — evaluate condition
                boolean conditionMet = evaluateActionConditions(actionMetadata, eventData);
                if (conditionMet) {
                    finalMatches.add(match);
                }
            }
        }

        // Evaluate condition-only triggers (Scenario 5: F3 only)
        for (ConditionOnlyTrigger trigger : conditionOnlyTriggers) {
            boolean result = expressionEvaluationService.evaluate(
                    trigger.conditionLanguage(), trigger.conditionExpression(), eventData);
            if (result) {
                finalMatches.add(new MatchedAction(trigger.protocolDefinitionId(), trigger.actionId()));
            }
        }

        return finalMatches;
    }

    private boolean evaluateActionConditions(PlanDefinitionParser.ActionMetadata actionMetadata,
                                             JsonNode eventData) {

        for (PlanDefinitionParser.TriggerInfo trigger : actionMetadata.triggers()) {
            if (trigger.condition() != null) {
                boolean result = expressionEvaluationService.evaluate(
                        trigger.condition().language(),
                        trigger.condition().expression(),
                        eventData);
                if (result) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processMatch(MatchedAction match, CloudEventMessage event, EventLog eventLog,
                              Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        ProtocolDefinition protocolDef = protocolDefinitionService.findById(match.protocolDefinitionId());
        String patientId = event.getSubject();

        // Enroll patient (idempotent — returns existing if already enrolled)
        ProtocolInstance protocolInstance = protocolInstanceService.enrollPatient(
                patientId, protocolDef, OffsetDateTime.now(ZoneOffset.UTC));

        // Find or create an actionable step
        StepInstance step = stepInstanceService.findActionableStep(
                protocolInstance.getId(), match.actionId());
        if (step == null) {
            step = createInitialStep(protocolInstance, match.actionId(), actionCache);
        }

        // Complete the step
        stepInstanceService.completeStep(step, eventLog.getId(), event.getSource());

        // Evaluate intelligence rules on step completion
        intelligenceRuleService.evaluateOnCompletion(step, protocolInstance);

        auditService.audit("COMPLIANCE", "EVENT_MATCHED", "system",
                "EventLog", eventLog.getId().toString(),
                Map.of("protocolDefinitionId", match.protocolDefinitionId().toString(),
                        "actionId", match.actionId(),
                        "protocolInstanceId", protocolInstance.getId().toString(),
                        "patientId", patientId));
    }

    private StepInstance createInitialStep(ProtocolInstance protocolInstance, String actionId,
                                           Map<UUID, List<PlanDefinitionParser.ActionMetadata>> actionCache) {
        UUID protocolDefId = protocolInstance.getProtocolDefinition().getId();
        var actions = getActionsForProtocol(protocolDefId, actionCache);

        var actionMetadata = actions.stream()
                .filter(a -> actionId.equals(a.id()))
                .findFirst()
                .orElse(null);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime overdueDate = null;
        OffsetDateTime missedDate = null;
        String requiredBehavior = null;

        if (actionMetadata != null) {
            requiredBehavior = actionMetadata.requiredBehavior();
            if (actionMetadata.toleranceDays() != null) {
                overdueDate = now.plusDays(actionMetadata.toleranceDays());
                missedDate = overdueDate.plusDays(actionMetadata.toleranceDays());
            }
        }

        return stepInstanceService.createStep(protocolInstance, actionId, 0,
                now, overdueDate, missedDate, requiredBehavior);
    }

    /**
     * Cache-backed lookup of parsed action metadata for a protocol definition.
     * Avoids redundant PlanDefinition JSON parsing within a single event processing cycle.
     */
    private List<PlanDefinitionParser.ActionMetadata> getActionsForProtocol(
            UUID protocolDefId, Map<UUID, List<PlanDefinitionParser.ActionMetadata>> cache) {
        return cache.computeIfAbsent(protocolDefId, id -> {
            ProtocolDefinition protocolDef = protocolDefinitionService.findById(id);
            var planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());
            return planDefinitionParser.extractActions(planDefinition);
        });
    }
}
