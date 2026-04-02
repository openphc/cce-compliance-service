package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.fhir.ExpressionEvaluationService;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.IntelligenceTriggerEvent;
import org.openphc.cce.compliance.kafka.producer.IntelligenceTriggerProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Evaluates intelligence rules (nested sub-actions in PlanDefinition steps)
 * on deviation detection and step completion. When a rule fires, publishes
 * an IntelligenceTriggerEvent to Kafka and creates an ActionRun record.
 */
@Service
public class IntelligenceRuleService {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceRuleService.class);

    private static final String SEVERITY_EXT_URL = "http://openphc.org/fhir/StructureDefinition/intelligence-severity";
    private static final String TARGET_EXT_URL = "http://openphc.org/fhir/StructureDefinition/intelligence-target";

    private final PlanDefinitionParser planDefinitionParser;
    private final ExpressionEvaluationService expressionEvaluationService;
    private final ActionDefinitionService actionDefinitionService;
    private final ActionRunService actionRunService;
    private final IntelligenceTriggerProducer intelligenceTriggerProducer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Counter ruleEvaluationErrorCounter;
    private final Counter rulesFiredCounter;

    public IntelligenceRuleService(PlanDefinitionParser planDefinitionParser,
                                    ExpressionEvaluationService expressionEvaluationService,
                                    ActionDefinitionService actionDefinitionService,
                                    ActionRunService actionRunService,
                                    IntelligenceTriggerProducer intelligenceTriggerProducer,
                                    AuditService auditService,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.planDefinitionParser = planDefinitionParser;
        this.expressionEvaluationService = expressionEvaluationService;
        this.actionDefinitionService = actionDefinitionService;
        this.actionRunService = actionRunService;
        this.intelligenceTriggerProducer = intelligenceTriggerProducer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.ruleEvaluationErrorCounter = meterRegistry.counter("cce.intelligence.rule.evaluation.errors");
        this.rulesFiredCounter = meterRegistry.counter("cce.intelligence.rules.fired");
    }

    /**
     * Evaluate intelligence rules for a step after a deviation is recorded.
     */
    public void evaluateOnDeviation(StepInstance step, Deviation deviation,
                                     ProtocolInstance protocolInstance) {
        Map<String, Object> context = buildDeviationContext(step, deviation, protocolInstance);
        evaluateRules(step, protocolInstance, deviation, context);
    }

    /**
     * Evaluate intelligence rules for a step after completion (e.g., late completion alerts).
     */
    public void evaluateOnCompletion(StepInstance step, ProtocolInstance protocolInstance) {
        Map<String, Object> context = buildCompletionContext(step, protocolInstance);
        evaluateRules(step, protocolInstance, null, context);
    }

    private void evaluateRules(StepInstance step, ProtocolInstance protocolInstance,
                                Deviation deviation, Map<String, Object> context) {
        JsonNode definition = protocolInstance.getProtocolDefinition().getDefinition();
        PlanDefinition planDefinition;
        try {
            planDefinition = planDefinitionParser.parse(definition.toString());
        } catch (Exception e) {
            ruleEvaluationErrorCounter.increment();
            log.error("Failed to parse PlanDefinition for intelligence rule evaluation: protocolDefId={}",
                    protocolInstance.getProtocolDefinition().getId(), e);
            return;
        }

        // Find the action matching this step
        PlanDefinition.PlanDefinitionActionComponent targetAction = planDefinition.getAction().stream()
                .filter(a -> step.getActionId().equals(a.getId()))
                .findFirst()
                .orElse(null);

        if (targetAction == null || targetAction.getAction().isEmpty()) {
            return;
        }

        // Nested sub-actions are intelligence rules
        JsonNode contextNode = objectMapper.valueToTree(context);

        for (PlanDefinition.PlanDefinitionActionComponent subAction : targetAction.getAction()) {
            evaluateSubAction(subAction, contextNode, step, protocolInstance, deviation);
        }
    }

    private void evaluateSubAction(PlanDefinition.PlanDefinitionActionComponent subAction,
                                    JsonNode contextNode, StepInstance step,
                                    ProtocolInstance protocolInstance, Deviation deviation) {
        // Check applicability condition
        if (subAction.hasCondition()) {
            boolean conditionMet = subAction.getCondition().stream()
                    .filter(c -> "applicability".equals(c.getKind().toCode()))
                    .allMatch(c -> {
                        String lang = c.getExpression().getLanguage();
                        String expr = c.getExpression().getExpression();
                        return expressionEvaluationService.evaluate(lang, expr, contextNode);
                    });

            if (!conditionMet) {
                return;
            }
        }

        // Extract severity and target from extensions
        String severity = extractExtensionValue(subAction, SEVERITY_EXT_URL);
        String target = extractExtensionValue(subAction, TARGET_EXT_URL);
        String definitionCanonical = subAction.hasDefinitionCanonicalType()
                ? subAction.getDefinitionCanonicalType().getValue()
                : null;

        IntelligenceSeverity severityEnum = severity != null
                ? IntelligenceSeverity.valueOf(severity.toUpperCase())
                : IntelligenceSeverity.MEDIUM;
        IntelligenceTarget targetEnum = target != null
                ? IntelligenceTarget.valueOf(target.toUpperCase())
                : IntelligenceTarget.ASSIGNED_WORKER;

        // Build and publish intelligence trigger event
        UUID eventId = UUID.randomUUID();
        String eventType = deviation != null
                ? "cce.compliance.deviation." + deviation.getDeviationType().name().toLowerCase()
                : "cce.compliance.intelligence.notification";

        IntelligenceTriggerEvent triggerEvent = IntelligenceTriggerEvent.builder()
                .id(eventId)
                .type(eventType)
                .subject(protocolInstance.getPatientId())
                .protocolInstanceId(protocolInstance.getId())
                .stepInstanceId(step.getId())
                .deviationId(deviation != null ? deviation.getId() : null)
                .deviationType(deviation != null ? deviation.getDeviationType().name().toLowerCase() : null)
                .stepState(step.getState().name().toLowerCase())
                .actionId(step.getActionId())
                .protocolCanonical(protocolInstance.getProtocolCanonical())
                .detectedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .severity(severityEnum.name().toLowerCase())
                .target(targetEnum.name().toLowerCase())
                .definitionCanonical(definitionCanonical)
                .build();

        // Resolve action definition if registered
        ActionDefinition actionDef = null;
        if (definitionCanonical != null) {
            actionDef = actionDefinitionService.findByDefinitionCanonical(definitionCanonical)
                    .orElse(null);
            if (actionDef != null) {
                triggerEvent.setActionDefinitionId(actionDef.getId());
            }
        }

        // Publish to Kafka
        intelligenceTriggerProducer.publish(triggerEvent);
        rulesFiredCounter.increment();

        // Update deviation with intelligence_event_id
        if (deviation != null) {
            deviation.setIntelligenceEventId(eventId);
        }

        // Create ActionRun record if we have an action definition
        if (actionDef != null) {
            String resolvedMessage = resolveMessageTemplate(
                    actionDef.getMessageTemplate(), protocolInstance, step, deviation);

            ActionRun actionRun = ActionRun.builder()
                    .actionDefinition(actionDef)
                    .intelligenceEventId(eventId)
                    .status(ActionRunStatus.PENDING)
                    .patientId(protocolInstance.getPatientId())
                    .protocolInstanceId(protocolInstance.getId())
                    .stepInstanceId(step.getId())
                    .actionType(actionDef.getActionType())
                    .severity(severityEnum)
                    .target(targetEnum)
                    .resolvedMessage(resolvedMessage)
                    .build();

            actionRunService.save(actionRun);

            log.info("Created action run for intelligence rule: ruleId={}, eventId={}, actionDefId={}",
                    subAction.getId(), eventId, actionDef.getId());
        }

        auditService.audit("INTELLIGENCE", "INTELLIGENCE_PUBLISHED", "system",
                "IntelligenceTriggerEvent", eventId.toString(),
                Map.of("type", eventType,
                        "severity", severityEnum.name(),
                        "target", targetEnum.name(),
                        "stepInstanceId", step.getId().toString(),
                        "protocolInstanceId", protocolInstance.getId().toString()));

        log.info("Intelligence rule fired: ruleId={}, eventId={}, type={}, severity={}, target={}",
                subAction.getId(), eventId, eventType, severityEnum, targetEnum);
    }

    private Map<String, Object> buildDeviationContext(StepInstance step, Deviation deviation,
                                                       ProtocolInstance protocolInstance) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepState", step.getState().name().toLowerCase());
        context.put("actionId", step.getActionId());

        if (deviation.getDeviationType() == DeviationType.OVERDUE && step.getDueDate() != null) {
            context.put("daysOverdue",
                    Duration.between(step.getDueDate(), OffsetDateTime.now(ZoneOffset.UTC)).toDays());
        }
        if (deviation.getDeviationType() == DeviationType.MISSED && step.getMissedDate() != null) {
            context.put("daysPastMissedDate",
                    Duration.between(step.getMissedDate(), OffsetDateTime.now(ZoneOffset.UTC)).toDays());
        }

        context.put("patient", Map.of("patientId", protocolInstance.getPatientId()));
        context.put("protocol", Map.of(
                "protocolCanonical", protocolInstance.getProtocolCanonical(),
                "status", protocolInstance.getStatus().name().toLowerCase()));

        return context;
    }

    private Map<String, Object> buildCompletionContext(StepInstance step,
                                                        ProtocolInstance protocolInstance) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepState", step.getState().name().toLowerCase());
        context.put("actionId", step.getActionId());
        context.put("completionStatus", step.getCompletionStatus() != null
                ? step.getCompletionStatus().name().toLowerCase() : null);

        if (step.getDueDate() != null) {
            context.put("daysOverdue",
                    Duration.between(step.getDueDate(), OffsetDateTime.now(ZoneOffset.UTC)).toDays());
        }

        context.put("patient", Map.of("patientId", protocolInstance.getPatientId()));
        context.put("protocol", Map.of(
                "protocolCanonical", protocolInstance.getProtocolCanonical(),
                "status", protocolInstance.getStatus().name().toLowerCase()));

        return context;
    }

    private String extractExtensionValue(PlanDefinition.PlanDefinitionActionComponent action, String url) {
        Extension ext = action.getExtensionByUrl(url);
        if (ext != null && ext.hasValue()) {
            return ext.getValue().primitiveValue();
        }
        return null;
    }

    private String resolveMessageTemplate(String template, ProtocolInstance protocolInstance,
                                           StepInstance step, Deviation deviation) {
        if (template == null || template.isBlank()) {
            return null;
        }

        String resolved = template;
        resolved = resolved.replace("{{patientId}}", protocolInstance.getPatientId());
        resolved = resolved.replace("{{actionId}}", step.getActionId());
        resolved = resolved.replace("{{protocolCanonical}}", protocolInstance.getProtocolCanonical());

        if (step.getDueDate() != null) {
            resolved = resolved.replace("{{dueDate}}", step.getDueDate().toString());
        }
        if (step.getDueDate() != null) {
            long daysOverdue = Duration.between(step.getDueDate(), OffsetDateTime.now(ZoneOffset.UTC)).toDays();
            resolved = resolved.replace("{{daysOverdue}}", String.valueOf(daysOverdue));
        }

        return resolved;
    }
}
