package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.CompletionStatus;
import org.openphc.cce.compliance.domain.enums.DeviationType;
import org.openphc.cce.compliance.domain.enums.StepState;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private static final Set<StepState> ACTIONABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);

    private final StepInstanceRepository stepInstanceRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final DeviationService deviationService;
    private final AuditService auditService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final StateTransitionHistoryService stateTransitionHistoryService;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               PlanDefinitionParser planDefinitionParser,
                               DeviationService deviationService,
                               AuditService auditService,
                               IntelligenceActionEvaluator intelligenceActionEvaluator,
                               StateTransitionHistoryService stateTransitionHistoryService) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.deviationService = deviationService;
        this.auditService = auditService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryService = stateTransitionHistoryService;
    }

    /**
     * Create a new step instance in PENDING state.
     */
    public StepInstance createStep(ProtocolInstance protocolInstance, String actionId,
                                   int repeatIndex, OffsetDateTime dueDate,
                                   OffsetDateTime overdueDate, OffsetDateTime missedDate,
                                   String requiredBehavior) {
        StepInstance step = StepInstance.builder()
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(repeatIndex)
                .state(StepState.PENDING)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .missedDate(missedDate)
                .requiredBehavior(requiredBehavior)
                .build();

        step = stepInstanceRepository.save(step);

        // Capture the initial PENDING state in append-only history.
        stateTransitionHistoryService.recordStepInstanceTransition(step, step.getCreatedAt());

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}",
                actionId, repeatIndex, protocolInstance.getId(), step.getId());

        return step;
    }

    /**
     * Complete a step instance. Determines completion status based on timing,
     * triggers progressive step instantiation for dependent steps, materializes mandatory
     * steps the journey never recorded (see {@link #backfillMissingMandatorySteps}), and checks
     * if the protocol is now complete.
     */
    public void completeStep(StepInstance step, UUID matchedEventId, String completedBySource,
                             OffsetDateTime occurredAt) {
        if (!ACTIONABLE_STATES.contains(step.getState())) {
            throw new IllegalStateException(
                    "Cannot complete step in state " + step.getState() + ": " + step.getId());
        }

        // completedAt is the clinical occurrence time (when the act happened), not the ingestion
        // time — so dependent steps' due/overdue/missed dates and the completion status reflect
        // real-world timing rather than how long the event took to reach us. Clamp to now(): a step
        // cannot have completed in the future, and a bad/future source clock must not push
        // downstream schedules out. Fall back to now() when no occurrence time was resolved.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime completedAt = (occurredAt != null && !occurredAt.isAfter(now)) ? occurredAt : now;
        CompletionStatus completionStatus = determineCompletionStatus(step, completedAt);
        step.setState(StepState.COMPLETED);
        step.setCompletedAt(completedAt);
        step.setCompletedByEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);
        step.setCompletionStatus(completionStatus);

        stepInstanceRepository.save(step);

        // Capture the COMPLETED transition in append-only history.
        stateTransitionHistoryService.recordStepInstanceTransition(step, now);

        auditService.audit("COMPLIANCE", "STEP_COMPLETED", "system",
                "StepInstance", step.getId().toString(),
                Map.of("actionId", step.getActionId(),
                        "completionStatus", step.getCompletionStatus().name(),
                        "protocolInstanceId", step.getProtocolInstance().getId().toString()));

        log.info("Completed step: stepId={}, actionId={}, completionStatus={}",
                step.getId(), step.getActionId(), step.getCompletionStatus());

        // Parse protocol definition once — reused by progressive instantiation
        JsonNode definition = step.getProtocolInstance().getProtocolDefinition().getDefinition();
        PlanDefinition planDefinition = planDefinitionParser.parse(definition.toString());
        List<PlanDefinitionParser.StepMetadata> steps = planDefinitionParser.extractSteps(planDefinition);

        // Detect order violations (must-have prerequisites still incomplete)
        detectOrderViolations(step, steps);

        // Progressive step instantiation for dependent steps
        createDependentSteps(step, steps);

        // Auto-skip preceding optional (could) steps that are still actionable
        autoSkipPrecedingOptionalSteps(step, steps);

        // Materialize mandatory predecessors this journey never recorded
        backfillMissingMandatorySteps(step, steps);
    }

    /**
     * Apply a scheduler-driven state transition.
     * Creates deviations for OVERDUE and MISSED transitions.
     */
    public void applySchedulerTransition(SchedulerTriggerMessage trigger) {
        StepInstance step = findByIdOrThrow(trigger.getStepInstanceId());

        switch (trigger.getTransitionType()) {
            case "PENDING_TO_DUE" -> applyTransition(step, StepState.PENDING, StepState.DUE);
            case "DUE_TO_OVERDUE" -> {
                // Only raise a deviation if the transition actually happened. A redelivered
                // or duplicate scheduler trigger finds the step already OVERDUE, so
                // applyTransition returns false and we skip the (otherwise duplicate) deviation.
                if (applyTransition(step, StepState.DUE, StepState.OVERDUE)) {
                    DeviationService.DeviationResult result =
                            deviationService.createDeviation(step, DeviationType.OVERDUE);
                    // Only evaluate intelligence for a freshly created deviation. If a
                    // concurrent thread already created it, skip to avoid a duplicate event.
                    if (result.created()) {
                        intelligenceActionEvaluator.evaluateOnDeviation(step, result.deviation());
                    }
                }
            }
            case "OVERDUE_TO_MISSED" -> {
                if ("could".equals(step.getRequiredBehavior())) {
                    if (applyTransition(step, StepState.OVERDUE, StepState.SKIPPED)) {
                        log.info("Optional step {} skipped instead of missed (requiredBehavior=could)",
                                step.getId());
                    }
                } else if (applyTransition(step, StepState.OVERDUE, StepState.MISSED)) {
                    DeviationService.DeviationResult result =
                            deviationService.createDeviation(step, DeviationType.MISSED);
                    if (result.created()) {
                        intelligenceActionEvaluator.evaluateOnDeviation(step, result.deviation());
                    }
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unknown transition type: " + trigger.getTransitionType());
        }
    }

    @Transactional(readOnly = true)
    public StepInstance findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<StepInstance> findByProtocolInstanceId(UUID protocolInstanceId) {
        return stepInstanceRepository.findByProtocolInstanceId(protocolInstanceId);
    }

    /**
     * Find the first actionable step (PENDING, DUE, OVERDUE) for a given protocol instance and actionId.
     * Returns null if no actionable step exists.
     */
    @Transactional(readOnly = true)
    public StepInstance findActionableStep(UUID protocolInstanceId, String actionId) {
        List<StepInstance> steps = stepInstanceRepository
                .findByProtocolInstanceIdAndActionIdAndStateIn(protocolInstanceId, actionId, ACTIONABLE_STATES);
        return steps.isEmpty() ? null : steps.get(0);
    }

    /**
     * Apply a state transition if the step is in the expected state.
     *
     * @return true if the transition was applied, false if it was skipped because the
     *         step was not in the expected state (e.g. a redelivered/duplicate trigger).
     */
    private boolean applyTransition(StepInstance step, StepState expectedState, StepState newState) {
        if (step.getState() != expectedState) {
            log.warn("Step {} is in state {} — expected {} for transition to {}. Skipping.",
                    step.getId(), step.getState(), expectedState, newState);
            return false;
        }

        step.setState(newState);
        stepInstanceRepository.save(step);

        // Capture the scheduler-driven transition in append-only history.
        stateTransitionHistoryService.recordStepInstanceTransition(step, OffsetDateTime.now(ZoneOffset.UTC));

        log.info("Transitioned step {} from {} to {} (actionId={})",
                step.getId(), expectedState, newState, step.getActionId());
        return true;
    }



    /**
     * Detect order violations: when a step completes, check if any of its own
     * declared predecessors (with requiredBehavior="must") are still in
     * non-terminal incomplete states (PENDING, DUE, OVERDUE).
     * A predecessor of step X is an action named in X's own relatedSteps list
     * (X is declared to come after it) - not a step that names X as ITS
     * predecessor, which would be a successor of X, not a predecessor.
     */
    private void detectOrderViolations(StepInstance completedStep,
                                       List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();
        String completedStepId = completedStep.getActionId();

        // Find this step's own declared predecessors - actions named in its own
        // relatedSteps - restricted to those with requiredBehavior="must"
        PlanDefinitionParser.StepMetadata completedMetadata = steps.stream()
                .filter(s -> completedStepId.equals(s.id()))
                .findFirst()
                .orElse(null);

        if (completedMetadata == null || completedMetadata.relatedSteps().isEmpty()) {
            return;
        }

        List<String> mustPredecessorIds = completedMetadata.relatedSteps().stream()
                .map(PlanDefinitionParser.RelatedStepInfo::actionId)
                .filter(predecessorId -> steps.stream()
                        .anyMatch(s -> predecessorId.equals(s.id()) && "must".equals(s.requiredBehavior())))
                .toList();

        if (mustPredecessorIds.isEmpty()) {
            return;
        }

        // Check if any must-predecessor steps are still in actionable (incomplete) states
        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(protocolInstance.getId());

        List<String> incompletePrerequisites = new ArrayList<>();
        for (String predecessorId : mustPredecessorIds) {
            boolean hasIncomplete = siblings.stream()
                    .filter(s -> predecessorId.equals(s.getActionId()))
                    .anyMatch(s -> ACTIONABLE_STATES.contains(s.getState()));
            if (hasIncomplete) {
                incompletePrerequisites.add(predecessorId);
            }
        }

        if (!incompletePrerequisites.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("incompletePrerequisites", incompletePrerequisites);
            // Key kept as-is: it is persisted in deviation.metadata and read downstream.
            metadata.put("completedActionId", completedStepId);

            DeviationService.DeviationResult result = deviationService.createDeviation(completedStep,
                    DeviationType.ORDER_VIOLATION, metadata);

            // Skip the warning + intelligence evaluation if this order violation was already
            // recorded (idempotent under redelivered / concurrent completion processing).
            if (result.created()) {
                log.warn("Order violation detected: step {} (actionId={}) completed while "
                                + "prerequisite steps {} are still incomplete",
                        completedStep.getId(), completedStepId, incompletePrerequisites);

                intelligenceActionEvaluator.evaluateOnDeviation(completedStep, result.deviation());
            }
        }
    }

    /**
     * Progressive step instantiation: when a step completes, create dependent PENDING
     * steps from relatedStep definitions with calculated due dates.
     */
    private void createDependentSteps(StepInstance completedStep,
                                      List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Find the completed step's metadata to get its relatedSteps
        PlanDefinitionParser.StepMetadata completedStepMetadata = steps.stream()
                .filter(s -> completedStep.getActionId().equals(s.id()))
                .findFirst()
                .orElse(null);

        if (completedStepMetadata == null || completedStepMetadata.relatedSteps().isEmpty()) {
            return;
        }

        for (PlanDefinitionParser.RelatedStepInfo relatedStep : completedStepMetadata.relatedSteps()) {
            // Dedup guard: skip if an instance for this step already exists.
            // A target may already exist because it was created reactively by its own
            // trigger (createInitialStep) before its predecessor completed, or because a
            // redelivered event re-completed the predecessor. Without this guard, progressive
            // instantiation would create a duplicate step that later goes overdue/missed and
            // raises a spurious deviation.
            if (stepInstanceRepository.existsByProtocolInstanceIdAndActionId(
                    protocolInstance.getId(), relatedStep.actionId())) {
                log.debug("Skipping progressive instantiation of {} — an instance for this step already exists (instanceId={})",
                        relatedStep.actionId(), protocolInstance.getId());
                continue;
            }

            // after-start: offset from when predecessor became active (dueDate)
            // after-end (default): offset from when predecessor completed (completedAt)
            OffsetDateTime baseTime = "after-start".equals(relatedStep.relationship())
                    ? completedStep.getDueDate()
                    : completedStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, relatedStep);

            // Find the target step's timing for overdue/missed dates
            PlanDefinitionParser.StepMetadata targetStep = steps.stream()
                    .filter(s -> relatedStep.actionId().equals(s.id()))
                    .findFirst()
                    .orElse(null);

            String requiredBehavior = targetStep != null ? targetStep.requiredBehavior() : null;

            // Only pre-create PENDING instances for mandatory (must) steps. Optional steps
            // (could / unspecified) are not instantiated on predecessor completion:
            // we may never receive their events, and a dangling PENDING row would later be
            // driven to OVERDUE/MISSED and raise a spurious deviation. When an optional step's
            // event does arrive, ComplianceEngine.processMatch creates the instance on the fly
            // (createInitialStep) and completes it in the same transaction.
            if (!"must".equals(requiredBehavior)) {
                log.debug("Skipping progressive instantiation of non-mandatory step {} "
                                + "(requiredBehavior={}, instanceId={})",
                        relatedStep.actionId(), requiredBehavior, protocolInstance.getId());
                continue;
            }

            // Create step instances — if timing specifies recurring, create N instances
            int repeatCount = 1;
            java.math.BigDecimal repeatPeriod = null;
            String repeatPeriodUnit = null;
            if (targetStep != null && targetStep.timing() != null) {
                PlanDefinitionParser.TimingInfo timing = targetStep.timing();
                if (timing.count() != null && timing.count() > 1) {
                    repeatCount = timing.count();
                    repeatPeriod = timing.period();
                    repeatPeriodUnit = timing.periodUnit();
                }
            }

            for (int i = 0; i < repeatCount; i++) {
                OffsetDateTime instanceDueDate = dueDate;
                if (i > 0 && repeatPeriod != null && repeatPeriodUnit != null) {
                    instanceDueDate = addOffset(dueDate, repeatPeriod.longValue() * i, repeatPeriodUnit);
                }

                OffsetDateTime instanceOverdueDate = null;
                OffsetDateTime instanceMissedDate = null;
                if (targetStep != null && targetStep.toleranceDays() != null) {
                    instanceOverdueDate = instanceDueDate.plusDays(targetStep.toleranceDays());
                    instanceMissedDate = instanceOverdueDate.plusDays(targetStep.toleranceDays());
                }

                createStep(protocolInstance, relatedStep.actionId(),
                        i, instanceDueDate, instanceOverdueDate, instanceMissedDate,
                        requiredBehavior);

                log.info("Progressive instantiation: created step {} (repeat {}/{}) due at {} (triggered by {}, relationship={})",
                        relatedStep.actionId(), i, repeatCount, instanceDueDate,
                        completedStep.getActionId(), relatedStep.relationship());
            }
        }
    }

    /**
     * Auto-skip preceding optional steps when a subsequent step completes.
     * Only skips steps that are direct ancestors (predecessors in the dependency graph)
     * of the completed step AND have requiredBehavior=could AND are still actionable.
     *
     * A predecessor of step X is any step whose relatedSteps list contains X
     * (i.e., completing that step would create X). This is computed transitively
     * to cover the full ancestor chain.
     */
    private void autoSkipPrecedingOptionalSteps(StepInstance completedStep,
                                                List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Compute all ancestor step ids of the completed step (transitive predecessors)
        Set<String> ancestorStepIds = PlanDefinitionParser.computeAncestors(
                completedStep.getActionId(), steps);

        if (ancestorStepIds.isEmpty()) {
            return;
        }

        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(protocolInstance.getId());

        for (StepInstance sibling : siblings) {
            if (sibling.getId().equals(completedStep.getId())) continue;
            if (!"could".equals(sibling.getRequiredBehavior())) continue;
            if (!ACTIONABLE_STATES.contains(sibling.getState())) continue;
            if (!ancestorStepIds.contains(sibling.getActionId())) continue;

            sibling.setState(StepState.SKIPPED);
            stepInstanceRepository.save(sibling);

            // Capture the auto-skip transition in append-only history.
            stateTransitionHistoryService.recordStepInstanceTransition(sibling, OffsetDateTime.now(ZoneOffset.UTC));

            log.info("Auto-skipped predecessor optional step {} (actionId={}) " +
                            "due to completion of step {} (actionId={})",
                    sibling.getId(), sibling.getActionId(),
                    completedStep.getId(), completedStep.getActionId());
        }
    }

    /**
     * Materialize the mandatory steps the journey should already have recorded. Progressive
     * instantiation only works forward from a completed step, so a step created reactively from its
     * own trigger (see {@code ComplianceEngine.createInitialStep}) leaves the mandatory steps that
     * should have preceded it with no row at all — they read as "not started" in the journey view
     * and are invisible to the scheduler, so they never surface as a deviation. On every completion,
     * any mandatory predecessor of the progress observed so far (see
     * {@link PlanDefinitionParser#computeMustPredecessorSteps}) that has no step instance is created
     * in PENDING state.
     *
     * <p>Scoped to predecessors — work that is already late — and never to mandatory steps still
     * ahead in the chain. Materializing those would stamp them with this completion's time and so
     * flatten the due dates their own {@code relatedAction} offsets define (e.g. lab-results' +3d
     * after lab-order); they are left to progressive instantiation, which creates them on their
     * predecessor's completion with the intended schedule.
     *
     * <p>Runs after {@link #detectOrderViolations} deliberately: backfilled rows must not count as
     * incomplete prerequisites for the completion that revealed them, so this does not invent an
     * ORDER_VIOLATION at completion time. A mandatory step that is never recorded is instead caught
     * by the scheduler driving the backfilled row OVERDUE and then MISSED. If its event does arrive
     * later, {@link #findActionableStep} picks the row up and completes it (LATE).
     *
     * <p>Idempotent: a mandatory step that already has any step instance — in any state, whether
     * pre-existing or created earlier in this same transaction by progressive instantiation — is
     * left alone.
     */
    private void backfillMissingMandatorySteps(StepInstance completedStep,
                                               List<PlanDefinitionParser.StepMetadata> steps) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        Set<String> observedStepIds = stepInstanceRepository
                .findByProtocolInstanceId(protocolInstance.getId()).stream()
                .map(StepInstance::getActionId)
                .collect(Collectors.toSet());

        List<String> missingMustSteps = PlanDefinitionParser
                .computeMustPredecessorSteps(observedStepIds, steps).stream()
                .filter(stepId -> !observedStepIds.contains(stepId))
                .sorted()
                .toList();

        if (missingMustSteps.isEmpty()) {
            return;
        }

        // Anchor to the clinical time of the completion that revealed the gap. Every backfilled step
        // is a prerequisite that should already have happened, so they are all equally past due —
        // there is no future schedule left to preserve among them — and this keeps them on clinical
        // time rather than the ingestion clock.
        OffsetDateTime dueDate = completedStep.getCompletedAt() != null
                ? completedStep.getCompletedAt()
                : OffsetDateTime.now(ZoneOffset.UTC);

        Map<String, PlanDefinitionParser.StepMetadata> stepsById = steps.stream()
                .collect(Collectors.toMap(PlanDefinitionParser.StepMetadata::id, s -> s, (a, b) -> a));

        for (String stepId : missingMustSteps) {
            PlanDefinitionParser.StepMetadata stepMetadata = stepsById.get(stepId);

            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            if (stepMetadata != null && stepMetadata.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(stepMetadata.toleranceDays());
                missedDate = overdueDate.plusDays(stepMetadata.toleranceDays());
            }

            // One instance (repeatIndex 0) regardless of the step's timing.repeat count: this is a
            // placeholder for work that was never recorded, not a scheduled recurrence.
            createStep(protocolInstance, stepId, 0, dueDate, overdueDate, missedDate, "must");

            log.info("Backfilled unrecorded mandatory step {} as PENDING due at {} "
                            + "(revealed by completion of {}, instanceId={})",
                    stepId, dueDate, completedStep.getActionId(), protocolInstance.getId());
        }
    }

    private OffsetDateTime calculateDueDate(OffsetDateTime baseTime,
                                            PlanDefinitionParser.RelatedStepInfo relatedStep) {
        if (relatedStep.offsetValue() == null) {
            return baseTime;
        }

        long offsetAmount = relatedStep.offsetValue().longValue();
        String unit = relatedStep.offsetUnit();

        if (unit == null) {
            return baseTime;
        }

        return addOffset(baseTime, offsetAmount, unit);
    }

    private OffsetDateTime addOffset(OffsetDateTime base, long amount, String unit) {
        return switch (unit) {
            case "d" -> base.plus(amount, ChronoUnit.DAYS);
            case "h" -> base.plus(amount, ChronoUnit.HOURS);
            case "min" -> base.plus(amount, ChronoUnit.MINUTES);
            case "wk" -> base.plus(amount * 7, ChronoUnit.DAYS);
            case "mo" -> base.plusMonths(amount);
            case "a" -> base.plusYears(amount);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    /**
     * Determine completion status based on timing:
     * EARLY (before dueDate), ON_TIME (between due and overdue), LATE (after overdueDate or state was OVERDUE).
     */
    private CompletionStatus determineCompletionStatus(StepInstance step, OffsetDateTime completedAt) {
        if (step.getState() == StepState.OVERDUE) {
            return CompletionStatus.LATE;
        }

        if (step.getDueDate() != null && completedAt.isBefore(step.getDueDate())) {
            return CompletionStatus.EARLY;
        }

        if (step.getOverdueDate() != null && completedAt.isAfter(step.getOverdueDate())) {
            return CompletionStatus.LATE;
        }

        return CompletionStatus.ON_TIME;
    }

    private StepInstance findByIdOrThrow(UUID id) {
        return stepInstanceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Step instance not found: " + id));
    }
}
