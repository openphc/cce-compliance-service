package org.openphc.cce.compliance.service;

import jakarta.persistence.EntityNotFoundException;
import org.openphc.cce.compliance.domain.entity.Deviation;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class StepInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StepInstanceService.class);

    private static final Set<StepState> ACTIONABLE_STATES = Set.of(
            StepState.PENDING, StepState.DUE, StepState.OVERDUE);

    private final StepInstanceRepository stepInstanceRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final ProtocolInstanceService protocolInstanceService;
    private final DeviationService deviationService;
    private final AuditService auditService;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;

    public StepInstanceService(StepInstanceRepository stepInstanceRepository,
                               PlanDefinitionParser planDefinitionParser,
                               ProtocolInstanceService protocolInstanceService,
                               DeviationService deviationService,
                               AuditService auditService,
                               IntelligenceActionEvaluator intelligenceActionEvaluator) {
        this.stepInstanceRepository = stepInstanceRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.protocolInstanceService = protocolInstanceService;
        this.deviationService = deviationService;
        this.auditService = auditService;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
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

        log.info("Created step instance: actionId={}, repeatIndex={}, instanceId={}, stepId={}",
                actionId, repeatIndex, protocolInstance.getId(), step.getId());

        return step;
    }

    /**
     * Create sub-step instances for a parent step that acts as a group.
     * Sub-steps are created in PENDING state with a reference to the parent step.
     */
    public List<StepInstance> createSubSteps(StepInstance parentStep,
                                             List<PlanDefinitionParser.SubStepActionInfo> subStepInfos) {
        List<StepInstance> subSteps = new ArrayList<>();
        OffsetDateTime parentDueDate = parentStep.getDueDate() != null
                ? parentStep.getDueDate()
                : OffsetDateTime.now(ZoneOffset.UTC);

        for (PlanDefinitionParser.SubStepActionInfo subStepInfo : subStepInfos) {
            // Only create sub-steps that have no relatedAction dependencies within the group
            // (i.e., root sub-steps). Dependent sub-steps are created progressively.
            boolean hasDependency = subStepInfos.stream()
                    .anyMatch(other -> other.relatedActions().stream()
                            .anyMatch(ra -> subStepInfo.id().equals(ra.actionId())));
            // If this sub-step is a target of another sub-step's relatedAction, skip initial creation
            // Actually, we want to find sub-steps that DO NOT depend on others (entry points)
            boolean dependsOnSibling = subStepInfo.relatedActions().stream()
                    .anyMatch(ra -> subStepInfos.stream().anyMatch(s -> s.id().equals(ra.actionId())));

            if (dependsOnSibling) {
                // This sub-step depends on a sibling — will be created by progressive instantiation
                continue;
            }

            OffsetDateTime dueDate = parentDueDate;
            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;

            if (subStepInfo.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(subStepInfo.toleranceDays());
                missedDate = overdueDate.plusDays(subStepInfo.toleranceDays());
            }

            StepInstance subStep = StepInstance.builder()
                    .protocolInstance(parentStep.getProtocolInstance())
                    .actionId(subStepInfo.id())
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .dueDate(dueDate)
                    .overdueDate(overdueDate)
                    .missedDate(missedDate)
                    .requiredBehavior(subStepInfo.requiredBehavior())
                    .parentStepId(parentStep.getId())
                    .parentActionId(parentStep.getActionId())
                    .build();

            subStep = stepInstanceRepository.save(subStep);
            subSteps.add(subStep);

            log.info("Created sub-step: actionId={}, parentStepId={}, parentActionId={}",
                    subStepInfo.id(), parentStep.getId(), parentStep.getActionId());
        }

        return subSteps;
    }

    /**
     * Complete a step instance. Determines completion status based on timing,
     * triggers progressive step instantiation for dependent steps, and checks
     * if the protocol is now complete.
     */
    public void completeStep(StepInstance step, UUID matchedEventId, String completedBySource) {
        if (!ACTIONABLE_STATES.contains(step.getState())) {
            throw new IllegalStateException(
                    "Cannot complete step in state " + step.getState() + ": " + step.getId());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CompletionStatus completionStatus = determineCompletionStatus(step, now);
        step.setState(StepState.COMPLETED);
        step.setCompletedAt(now);
        step.setMatchedEventId(matchedEventId);
        step.setCompletedBySource(completedBySource);
        step.setCompletionStatus(completionStatus);

        stepInstanceRepository.save(step);

        auditService.audit("COMPLIANCE", "STEP_COMPLETED", "system",
                "StepInstance", step.getId().toString(),
                Map.of("actionId", step.getActionId(),
                        "completionStatus", step.getCompletionStatus().name(),
                        "protocolInstanceId", step.getProtocolInstance().getId().toString()));

        log.info("Completed step: stepId={}, actionId={}, completionStatus={}",
                step.getId(), step.getActionId(), step.getCompletionStatus());

        // If this is a sub-step, handle group completion logic
        if (step.getParentStepId() != null) {
            createDependentSubSteps(step);
            evaluateGroupCompletion(step);
            return;
        }

        // Top-level step completion logic
        // Detect order violations (must-have prerequisites still incomplete)
        detectOrderViolations(step);

        // Progressive step instantiation
        createDependentSteps(step);

        // Auto-skip preceding optional (could) steps that are still actionable
        autoSkipPrecedingOptionalSteps(step);

        // Check if protocol is now complete
        protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
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
                applyTransition(step, StepState.DUE, StepState.OVERDUE);
                Deviation deviation = createDeviation(step, DeviationType.OVERDUE);
                intelligenceActionEvaluator.evaluateOnDeviation(step, deviation);
            }
            case "OVERDUE_TO_MISSED" -> {
                if ("could".equals(step.getRequiredBehavior())) {
                    applyTransition(step, StepState.OVERDUE, StepState.SKIPPED);
                    log.info("Optional step {} skipped instead of missed (requiredBehavior=could)",
                            step.getId());
                } else {
                    applyTransition(step, StepState.OVERDUE, StepState.MISSED);
                    Deviation deviation = createDeviation(step, DeviationType.MISSED);
                    intelligenceActionEvaluator.evaluateOnDeviation(step, deviation);
                }
                // Check if protocol is now complete (MISSED/SKIPPED are terminal)
                protocolInstanceService.checkAndCompleteProtocol(step.getProtocolInstance().getId());
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

    private void applyTransition(StepInstance step, StepState expectedState, StepState newState) {
        if (step.getState() != expectedState) {
            log.warn("Step {} is in state {} — expected {} for transition to {}. Skipping.",
                    step.getId(), step.getState(), expectedState, newState);
            return;
        }

        step.setState(newState);
        stepInstanceRepository.save(step);

        log.info("Transitioned step {} from {} to {} (actionId={})",
                step.getId(), expectedState, newState, step.getActionId());
    }

    private Deviation createDeviation(StepInstance step, DeviationType deviationType) {
        ProtocolInstance protocolInstance = step.getProtocolInstance();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (deviationType == DeviationType.OVERDUE && step.getDueDate() != null) {
            metadata.put("daysOverdue",
                    Duration.between(step.getDueDate(), now).toDays());
        }
        if (deviationType == DeviationType.MISSED && step.getMissedDate() != null) {
            metadata.put("daysPastMissedDate",
                    Duration.between(step.getMissedDate(), now).toDays());
        }

        return deviationService.recordDeviation(protocolInstance, step, deviationType,
                metadata.isEmpty() ? null : metadata);
    }

    /**
     * Detect order violations: when a step completes, check if any immediate
     * predecessor steps (with requiredBehavior="must") are still in
     * non-terminal incomplete states (PENDING, DUE, OVERDUE).
     * A predecessor of action X is any action whose relatedActions list contains X.
     */
    private void detectOrderViolations(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        var definition = protocolInstance.getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        String completedActionId = completedStep.getActionId();

        // Find immediate predecessors: actions whose relatedActions contain this action's id
        // and that have requiredBehavior="must"
        List<String> mustPredecessorIds = actions.stream()
                .filter(a -> "must".equals(a.requiredBehavior()))
                .filter(a -> a.relatedActions().stream()
                        .anyMatch(ra -> completedActionId.equals(ra.actionId())))
                .map(PlanDefinitionParser.ActionMetadata::id)
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
            metadata.put("completedActionId", completedActionId);

            Deviation deviation = deviationService.recordDeviation(
                    protocolInstance, completedStep,
                    DeviationType.ORDER_VIOLATION, metadata);

            log.warn("Order violation detected: step {} (actionId={}) completed while "
                            + "prerequisite steps {} are still incomplete",
                    completedStep.getId(), completedActionId, incompletePrerequisites);

            intelligenceActionEvaluator.evaluateOnDeviation(completedStep, deviation);
        }
    }

    /**
     * Progressive step instantiation: when a step completes, create dependent PENDING
     * steps from relatedAction definitions with calculated due dates.
     */
    private void createDependentSteps(StepInstance completedStep) {
        ProtocolInstance protocolInstance = completedStep.getProtocolInstance();

        // Parse the protocol definition to get relatedAction info
        var definition = protocolInstance.getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        // Find the completed action's metadata to get its relatedActions
        var completedActionMetadata = actions.stream()
                .filter(a -> completedStep.getActionId().equals(a.id()))
                .findFirst()
                .orElse(null);

        if (completedActionMetadata == null || completedActionMetadata.relatedActions().isEmpty()) {
            return;
        }

        for (PlanDefinitionParser.RelatedActionInfo relatedAction : completedActionMetadata.relatedActions()) {
            // after-start: offset from when predecessor became active (dueDate)
            // after-end (default): offset from when predecessor completed (completedAt)
            OffsetDateTime baseTime = "after-start".equals(relatedAction.relationship())
                    ? completedStep.getDueDate()
                    : completedStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, relatedAction);

            // Find the target action's timing for overdue/missed dates
            var targetAction = actions.stream()
                    .filter(a -> relatedAction.actionId().equals(a.id()))
                    .findFirst()
                    .orElse(null);

            String requiredBehavior = targetAction != null ? targetAction.requiredBehavior() : null;

            // Create step instances — if timing specifies recurring, create N instances
            int repeatCount = 1;
            java.math.BigDecimal repeatPeriod = null;
            String repeatPeriodUnit = null;
            if (targetAction != null && targetAction.timing() != null) {
                PlanDefinitionParser.TimingInfo timing = targetAction.timing();
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
                if (targetAction != null && targetAction.toleranceDays() != null) {
                    instanceOverdueDate = instanceDueDate.plusDays(targetAction.toleranceDays());
                    instanceMissedDate = instanceOverdueDate.plusDays(targetAction.toleranceDays());
                }

                createStep(protocolInstance, relatedAction.actionId(),
                        i, instanceDueDate, instanceOverdueDate, instanceMissedDate,
                        requiredBehavior);

                log.info("Progressive instantiation: created step {} (repeat {}/{}) due at {} (triggered by {}, relationship={})",
                        relatedAction.actionId(), i, repeatCount, instanceDueDate,
                        completedStep.getActionId(), relatedAction.relationship());
            }
        }
    }

    /**
     * Auto-skip preceding optional steps when a subsequent step completes.
     * Steps with requiredBehavior=could that are still in PENDING/DUE/OVERDUE
     * within the same protocol instance are automatically skipped.
     */
    private void autoSkipPrecedingOptionalSteps(StepInstance completedStep) {
        List<StepInstance> siblings = stepInstanceRepository
                .findByProtocolInstanceId(completedStep.getProtocolInstance().getId());

        for (StepInstance sibling : siblings) {
            if (sibling.getId().equals(completedStep.getId())) continue;
            if (!"could".equals(sibling.getRequiredBehavior())) continue;
            if (!ACTIONABLE_STATES.contains(sibling.getState())) continue;

            sibling.setState(StepState.SKIPPED);
            stepInstanceRepository.save(sibling);

            log.info("Auto-skipped optional step {} (actionId={}, requiredBehavior=could) " +
                            "due to completion of step {} (actionId={})",
                    sibling.getId(), sibling.getActionId(),
                    completedStep.getId(), completedStep.getActionId());
        }
    }

    /**
     * Create dependent sub-steps within the same parent group when a sibling sub-step completes.
     * Uses relatedAction definitions scoped within the parent's sub-step list.
     */
    private void createDependentSubSteps(StepInstance completedSubStep) {
        ProtocolInstance protocolInstance = completedSubStep.getProtocolInstance();
        UUID parentStepId = completedSubStep.getParentStepId();
        String parentActionId = completedSubStep.getParentActionId();

        // Parse protocol to find the parent action's sub-steps
        var definition = protocolInstance.getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        PlanDefinitionParser.ActionMetadata parentActionMetadata = actions.stream()
                .filter(a -> parentActionId.equals(a.id()))
                .findFirst()
                .orElse(null);

        if (parentActionMetadata == null || !parentActionMetadata.hasSubSteps()) {
            return;
        }

        // Find the completed sub-step's metadata
        PlanDefinitionParser.SubStepActionInfo completedSubStepInfo = parentActionMetadata.subSteps().stream()
                .filter(s -> completedSubStep.getActionId().equals(s.id()))
                .findFirst()
                .orElse(null);

        if (completedSubStepInfo == null || completedSubStepInfo.relatedActions().isEmpty()) {
            return;
        }

        // Create dependent sub-steps
        for (PlanDefinitionParser.RelatedActionInfo relatedAction : completedSubStepInfo.relatedActions()) {
            OffsetDateTime baseTime = "after-start".equals(relatedAction.relationship())
                    ? completedSubStep.getDueDate()
                    : completedSubStep.getCompletedAt();
            if (baseTime == null) {
                baseTime = completedSubStep.getCompletedAt();
            }
            OffsetDateTime dueDate = calculateDueDate(baseTime, relatedAction);

            // Find target sub-step info for tolerance
            PlanDefinitionParser.SubStepActionInfo targetSubStep = parentActionMetadata.subSteps().stream()
                    .filter(s -> relatedAction.actionId().equals(s.id()))
                    .findFirst()
                    .orElse(null);

            OffsetDateTime overdueDate = null;
            OffsetDateTime missedDate = null;
            String requiredBehavior = targetSubStep != null ? targetSubStep.requiredBehavior() : null;

            if (targetSubStep != null && targetSubStep.toleranceDays() != null) {
                overdueDate = dueDate.plusDays(targetSubStep.toleranceDays());
                missedDate = overdueDate.plusDays(targetSubStep.toleranceDays());
            }

            StepInstance subStep = StepInstance.builder()
                    .protocolInstance(protocolInstance)
                    .actionId(relatedAction.actionId())
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .dueDate(dueDate)
                    .overdueDate(overdueDate)
                    .missedDate(missedDate)
                    .requiredBehavior(requiredBehavior)
                    .parentStepId(parentStepId)
                    .parentActionId(parentActionId)
                    .build();

            subStep = stepInstanceRepository.save(subStep);

            log.info("Progressive sub-step instantiation: created sub-step {} due at {} " +
                            "(triggered by sub-step {}, parent={})",
                    relatedAction.actionId(), dueDate,
                    completedSubStep.getActionId(), parentActionId);
        }
    }

    /**
     * Evaluate group completion when a sub-step completes.
     * Uses the parent action's selectionBehavior to determine when the parent step is complete:
     * - "all" (default): all sub-steps must be completed
     * - "any": any single sub-step completion completes the parent
     * - "all-or-none": all must complete or none
     * - "exactly-one": exactly one sub-step completes the parent
     * - "at-most-one": at most one sub-step completes the parent
     * - "one-or-more": at least one sub-step completes the parent
     */
    private void evaluateGroupCompletion(StepInstance completedSubStep) {
        UUID parentStepId = completedSubStep.getParentStepId();
        StepInstance parentStep = findByIdOrThrow(parentStepId);

        // If parent is already completed, nothing to do
        if (!ACTIONABLE_STATES.contains(parentStep.getState())) {
            return;
        }

        // Get all sibling sub-steps
        List<StepInstance> childSteps = stepInstanceRepository.findByParentStepId(parentStepId);

        // Look up the parent action's selectionBehavior from the protocol definition
        String parentActionId = completedSubStep.getParentActionId();
        var definition = parentStep.getProtocolInstance().getProtocolDefinition().getDefinition();
        var planDefinition = planDefinitionParser.parse(definition.toString());
        var actions = planDefinitionParser.extractActions(planDefinition);

        PlanDefinitionParser.ActionMetadata parentActionMetadata = actions.stream()
                .filter(a -> parentActionId.equals(a.id()))
                .findFirst()
                .orElse(null);

        String selectionBehavior = parentActionMetadata != null
                ? parentActionMetadata.selectionBehavior()
                : null;

        boolean groupComplete = isGroupComplete(childSteps, selectionBehavior);

        if (groupComplete) {
            // Complete the parent step
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            CompletionStatus completionStatus = determineCompletionStatus(parentStep, now);
            parentStep.setState(StepState.COMPLETED);
            parentStep.setCompletedAt(now);
            parentStep.setCompletionStatus(completionStatus);
            stepInstanceRepository.save(parentStep);

            log.info("Group completed: parentStepId={}, parentActionId={}, selectionBehavior={}",
                    parentStepId, parentActionId, selectionBehavior);

            auditService.audit("COMPLIANCE", "GROUP_COMPLETED", "system",
                    "StepInstance", parentStep.getId().toString(),
                    Map.of("parentActionId", parentActionId,
                            "selectionBehavior", selectionBehavior != null ? selectionBehavior : "all",
                            "protocolInstanceId", parentStep.getProtocolInstance().getId().toString()));

            // Now run top-level step completion logic for the parent
            detectOrderViolations(parentStep);
            createDependentSteps(parentStep);
            autoSkipPrecedingOptionalSteps(parentStep);
            protocolInstanceService.checkAndCompleteProtocol(parentStep.getProtocolInstance().getId());
        }
    }

    private boolean isGroupComplete(List<StepInstance> childSteps, String selectionBehavior) {
        if (childSteps.isEmpty()) {
            return false;
        }

        long completedCount = childSteps.stream()
                .filter(s -> s.getState() == StepState.COMPLETED)
                .count();
        long terminalCount = childSteps.stream()
                .filter(s -> s.getState() == StepState.COMPLETED
                        || s.getState() == StepState.SKIPPED
                        || s.getState() == StepState.MISSED)
                .count();

        // Default behavior is "all"
        if (selectionBehavior == null || "all".equals(selectionBehavior)) {
            return terminalCount == childSteps.size() && completedCount > 0;
        }

        return switch (selectionBehavior) {
            case "any", "one-or-more" -> completedCount >= 1;
            case "exactly-one", "at-most-one" -> completedCount == 1;
            case "all-or-none" -> completedCount == childSteps.size() || completedCount == 0;
            default -> terminalCount == childSteps.size();
        };
    }

    private OffsetDateTime calculateDueDate(OffsetDateTime baseTime,
                                            PlanDefinitionParser.RelatedActionInfo relatedAction) {
        if (relatedAction.offsetValue() == null) {
            return baseTime;
        }

        long offsetAmount = relatedAction.offsetValue().longValue();
        String unit = relatedAction.offsetUnit();

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
