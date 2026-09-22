package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.compliance.domain.entity.Deviation;
import org.openphc.cce.compliance.domain.entity.ProtocolDefinition;
import org.openphc.cce.compliance.domain.entity.ProtocolInstance;
import org.openphc.cce.compliance.domain.entity.StepInstance;
import org.openphc.cce.compliance.domain.enums.*;
import org.openphc.cce.compliance.domain.repository.StepInstanceRepository;
import org.openphc.cce.compliance.fhir.PlanDefinitionParser;
import org.openphc.cce.compliance.kafka.model.SchedulerTriggerMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepInstanceServiceTest {

    @Mock
    private StepInstanceRepository stepInstanceRepository;

    @Mock
    private PlanDefinitionParser planDefinitionParser;

    @Mock
    private DeviationService deviationService;

    @Mock
    private AuditService auditService;

    @Mock
    private IntelligenceActionEvaluator intelligenceActionEvaluator;

    @Mock
    private StateTransitionHistoryService stateTransitionHistoryService;

    private StepInstanceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new StepInstanceService(stepInstanceRepository,
                planDefinitionParser, deviationService, auditService,
                intelligenceActionEvaluator, stateTransitionHistoryService);
    }

    @Nested
    class CreateStep {

        @Test
        void createsStepInPendingState() {
            ProtocolInstance protocolInstance = buildProtocolInstance();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            OffsetDateTime overdueDate = dueDate.plusDays(3);
            OffsetDateTime missedDate = overdueDate.plusDays(3);

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            StepInstance result = service.createStep(protocolInstance, "bp-check", 0,
                    dueDate, overdueDate, missedDate, "must");

            assertNotNull(result.getId());
            assertEquals("bp-check", result.getActionId());
            assertEquals(0, result.getRepeatIndex());
            assertEquals(StepState.PENDING, result.getState());
            assertEquals(dueDate, result.getDueDate());
            assertEquals(overdueDate, result.getOverdueDate());
            assertEquals(missedDate, result.getMissedDate());
            // The initial PENDING state is recorded in append-only history.
            verify(stateTransitionHistoryService).recordStepInstanceTransition(eq(result), any());
        }
    }

    @Nested
    class CompleteStep {

        @Test
        void pendingStep_completedBeforeDue_statusEarly() {
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStep(StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            UUID eventId = UUID.randomUUID();
            service.completeStep(step, eventId, "test-source", null);

            assertEquals(StepState.COMPLETED, step.getState());
            assertNotNull(step.getCompletedAt());
            assertEquals(eventId, step.getCompletedByEventId());
            assertEquals("test-source", step.getCompletedBySource());
            assertEquals(CompletionStatus.EARLY, step.getCompletionStatus());

            verify(auditService).audit(eq("COMPLIANCE"), eq("STEP_COMPLETED"),
                    eq("system"), eq("StepInstance"), anyString(), anyMap());
            // The COMPLETED transition (state + completion status) is recorded in append-only history.
            verify(stateTransitionHistoryService).recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }

        @Test
        void dueStep_completedOnTime_statusOnTime() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
            OffsetDateTime futureOverdue = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);
            StepInstance step = buildStep(StepState.DUE, pastDue, futureOverdue);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            assertEquals(StepState.COMPLETED, step.getState());
            assertEquals(CompletionStatus.ON_TIME, step.getCompletionStatus());
        }

        @Test
        void overdueStep_completedLate() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
            OffsetDateTime pastOverdue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
            StepInstance step = buildStep(StepState.OVERDUE, pastDue, pastOverdue);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            assertEquals(StepState.COMPLETED, step.getState());
            assertEquals(CompletionStatus.LATE, step.getCompletionStatus());
        }

        @Test
        void terminalState_throwsIllegalState() {
            StepInstance step = buildStep(StepState.COMPLETED, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.completeStep(step, UUID.randomUUID(), "src", null));
        }

        @Test
        void missedState_throwsIllegalState() {
            StepInstance step = buildStep(StepState.MISSED, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.completeStep(step, UUID.randomUUID(), "src", null));
        }
    }

    @Nested
    class ProgressiveInstantiation {

        @Test
        void completedStepWithRelatedActions_afterEnd_useCompletedAt() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            // Mock parser to return action metadata with relatedActions
            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("bp-check", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, null, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(), null, 3, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            // Verify: the completed step + the dependent step
            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(2)).save(captor.capture());

            List<StepInstance> savedSteps = captor.getAllValues();
            StepInstance dependentStep = savedSteps.stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(dependentStep, "Dependent step bp-check should be created");
            assertEquals(StepState.PENDING, dependentStep.getState());
            assertNotNull(dependentStep.getDueDate());
            assertNotNull(dependentStep.getOverdueDate());
            assertNotNull(dependentStep.getMissedDate());
            // after-end uses completedAt as base — dueDate should be ~7 days from completedAt
            assertTrue(dependentStep.getDueDate().isAfter(step.getCompletedAt().plusDays(6)));
        }

        @Test
        void completedStepWithRelatedActions_afterStart_useDueDate() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("bp-check", "after-start",
                                    BigDecimal.valueOf(14), "d")),
                            null, null, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(), null, 3, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(2)).save(captor.capture());

            StepInstance dependentStep = captor.getAllValues().stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(dependentStep, "Dependent step bp-check should be created");
            // after-start uses predecessor's dueDate as base
            OffsetDateTime expectedDue = dueDate.plusDays(14);
            assertEquals(expectedDue.toLocalDate(), dependentStep.getDueDate().toLocalDate());
        }

        @Test
        void recurringSteps_createsMultipleInstances() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            // Target action has timing: count=3, period=7 days
            PlanDefinitionParser.TimingInfo timing = new PlanDefinitionParser.TimingInfo(
                    3, 1, BigDecimal.valueOf(7), "d");

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("bp-check", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, null, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(), timing, 3, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeast(4)).save(captor.capture());

            List<StepInstance> bpSteps = captor.getAllValues().stream()
                    .filter(s -> "bp-check".equals(s.getActionId()))
                    .toList();

            assertEquals(3, bpSteps.size(), "Should create 3 recurring step instances");

            // Verify repeat indices
            assertEquals(0, bpSteps.get(0).getRepeatIndex());
            assertEquals(1, bpSteps.get(1).getRepeatIndex());
            assertEquals(2, bpSteps.get(2).getRepeatIndex());

            // Verify staggered due dates (each 7 days apart)
            OffsetDateTime firstDue = bpSteps.get(0).getDueDate();
            assertEquals(firstDue.plusDays(7).toLocalDate(), bpSteps.get(1).getDueDate().toLocalDate());
            assertEquals(firstDue.plusDays(14).toLocalDate(), bpSteps.get(2).getDueDate().toLocalDate());

            // Each should have overdue and missed dates
            for (StepInstance s : bpSteps) {
                assertNotNull(s.getOverdueDate());
                assertNotNull(s.getMissedDate());
            }
        }

        @Test
        void relatedStepAlreadyExists_isNotRecreated() {
            // Regression: a target step may already exist (created reactively by its own
            // trigger, or a redelivered event). Progressive instantiation must NOT create a
            // duplicate — otherwise the duplicate goes overdue/missed and raises a spurious
            // deviation. This also guarantees nesting (organizational only) never spawns
            // duplicate parent/sibling steps.
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStepWithProtocol(protocolInstance, "initial-enrollment",
                    StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any(StepInstance.class))).thenAnswer(invocation -> {
                StepInstance s = invocation.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(any())).thenReturn(List.of(step));

            // A bp-check step already exists for this instance
            when(stepInstanceRepository.existsByProtocolInstanceIdAndActionId(
                    protocolInstance.getId(), "bp-check")).thenReturn(true);

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("bp-check", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, null, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("bp-check", "BP Check",
                            List.of(), List.of(), null, 3, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source", null);

            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeastOnce()).save(captor.capture());

            boolean createdDuplicate = captor.getAllValues().stream()
                    .anyMatch(s -> "bp-check".equals(s.getActionId()));
            assertFalse(createdDuplicate, "Existing bp-check step must not be re-created");
        }
    }

    @Nested
    class SchedulerTransitions {

        @Test
        void pendingToDue_transitionsCorrectly() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.PENDING, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("PENDING_TO_DUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.DUE, step.getState());
            verify(deviationService, never()).createDeviation(any(), any());
            // The scheduler-driven DUE transition is recorded in append-only history.
            verify(stateTransitionHistoryService).recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }

        @Test
        void dueToOverdue_createsDeviation() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.DUE, null, null);
            step.setId(stepId);

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.OVERDUE)))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_OVERDUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.OVERDUE, step.getState());

            verify(deviationService).createDeviation(eq(step), eq(DeviationType.OVERDUE));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(step, deviation);
        }

        @Test
        void dueToOverdue_redelivered_createsDeviationOnlyOnce() {
            // Kafka is at-least-once: the same DUE_TO_OVERDUE trigger may arrive twice.
            // The first delivery transitions DUE->OVERDUE and raises the deviation; the
            // redelivery finds the step already OVERDUE, so applyTransition returns false
            // and no second (duplicate) deviation is created.
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.DUE, null, null);
            step.setId(stepId);

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.OVERDUE)))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_OVERDUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            // First delivery + redelivery of the same trigger
            service.applySchedulerTransition(trigger);
            service.applySchedulerTransition(trigger);

            assertEquals(StepState.OVERDUE, step.getState());
            verify(deviationService, times(1)).createDeviation(eq(step), eq(DeviationType.OVERDUE));
            verify(intelligenceActionEvaluator, times(1)).evaluateOnDeviation(step, deviation);
        }

        @Test
        void dueToOverdue_deviationAlreadyExisted_skipsIntelligenceEvaluation() {
            // Concurrent race: the transition applies, but createDeviation finds a deviation
            // another thread already created (created=false). Intelligence must NOT be
            // evaluated again, otherwise a duplicate intelligence event would fire.
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.DUE, null, null);
            step.setId(stepId);

            Deviation existing = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.OVERDUE)))
                    .thenReturn(new DeviationService.DeviationResult(existing, false));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_OVERDUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.OVERDUE, step.getState());
            verify(deviationService).createDeviation(eq(step), eq(DeviationType.OVERDUE));
            verify(intelligenceActionEvaluator, never()).evaluateOnDeviation(any(), any());
        }

        @Test
        void overdueToMissed_createsDeviationAndChecksProtocol() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.MISSED)))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());

            verify(deviationService).createDeviation(eq(step), eq(DeviationType.MISSED));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(step, deviation);

            // The MISSED transition is recorded in append-only history.
            verify(stateTransitionHistoryService).recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }

        @Test
        void wrongSourceState_skipsTransition() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.COMPLETED, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("PENDING_TO_DUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            // State should remain COMPLETED (transition skipped, not errored)
            assertEquals(StepState.COMPLETED, step.getState());
            verify(stepInstanceRepository, never()).save(any());
            // No state change → nothing recorded in history.
            verify(stateTransitionHistoryService, never()).recordStepInstanceTransition(any(), any());
        }

        @Test
        void unknownTransitionType_throwsIllegalArgument() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.PENDING, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("INVALID_TRANSITION")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            assertThrows(IllegalArgumentException.class,
                    () -> service.applySchedulerTransition(trigger));
        }

        @Test
        void stepNotFound_throwsEntityNotFound() {
            UUID stepId = UUID.randomUUID();
            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.empty());

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("PENDING_TO_DUE")
                    .build();

            assertThrows(EntityNotFoundException.class,
                    () -> service.applySchedulerTransition(trigger));
        }
    }

    @Nested
    class AutoSkipOptionalSteps {

        @Test
        void completingStep_autoSkipsAncestorCouldSteps() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            StepInstance optionalStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("optional-lab")
                    .repeatIndex(0)
                    .state(StepState.DUE)
                    .requiredBehavior("could")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("mandatory-visit")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(optionalStep, completedStep));

            // Build dependency graph: optional-lab → mandatory-visit (optional-lab is ancestor)
            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            PlanDefinitionParser.StepMetadata optionalLabAction = new PlanDefinitionParser.StepMetadata(
                    "optional-lab", "Optional Lab", null,
                    List.of(new PlanDefinitionParser.RelatedStepInfo("mandatory-visit", "after-end", BigDecimal.ZERO, "d")),
                    null, null, "could", List.of(), null);
            PlanDefinitionParser.StepMetadata mandatoryVisitAction = new PlanDefinitionParser.StepMetadata(
                    "mandatory-visit", "Mandatory Visit", null, List.of(), null, null, "must", List.of(), null);
            when(planDefinitionParser.extractSteps(mockPlanDef))
                    .thenReturn(List.of(optionalLabAction, mandatoryVisitAction));

            service.completeStep(completedStep, UUID.randomUUID(), "test-src", null);

            assertEquals(StepState.SKIPPED, optionalStep.getState());
            // The auto-skip transition of the ancestor optional step is recorded in append-only history.
            verify(stateTransitionHistoryService).recordStepInstanceTransition(eq(optionalStep), any(OffsetDateTime.class));
        }

        @Test
        void completingStep_doesNotSkipParallelCouldSiblings() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Parallel sibling: pregnancy-profile (created by same parent, not an ancestor of family-planning)
            StepInstance parallelSibling = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("pregnancy-profile")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("could")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("family-planning")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("could")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(parallelSibling, completedStep));

            // Graph: registration → family-planning, registration → pregnancy-profile (parallel branches)
            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            PlanDefinitionParser.StepMetadata registrationAction = new PlanDefinitionParser.StepMetadata(
                    "registration", "Registration", null,
                    List.of(new PlanDefinitionParser.RelatedStepInfo("family-planning", "after-end", BigDecimal.ZERO, "d"),
                            new PlanDefinitionParser.RelatedStepInfo("pregnancy-profile", "after-end", BigDecimal.ZERO, "d")),
                    null, null, "must", List.of(), null);
            PlanDefinitionParser.StepMetadata familyPlanningAction = new PlanDefinitionParser.StepMetadata(
                    "family-planning", "Family Planning", null, List.of(), null, null, "could", List.of(), null);
            PlanDefinitionParser.StepMetadata pregnancyProfileAction = new PlanDefinitionParser.StepMetadata(
                    "pregnancy-profile", "Pregnancy Profile", null, List.of(), null, null, "could", List.of(), null);
            when(planDefinitionParser.extractSteps(mockPlanDef))
                    .thenReturn(List.of(registrationAction, familyPlanningAction, pregnancyProfileAction));

            service.completeStep(completedStep, UUID.randomUUID(), "test-src", null);

            // pregnancy-profile should NOT be skipped — it's a parallel sibling, not an ancestor
            assertEquals(StepState.PENDING, parallelSibling.getState());
        }

        @Test
        void completingStep_doesNotSkipMustSteps() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            StepInstance mustStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("mandatory-lab")
                    .repeatIndex(0)
                    .state(StepState.DUE)
                    .requiredBehavior("must")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("visit")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(mustStep, completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(List.of());

            service.completeStep(completedStep, UUID.randomUUID(), "test-src", null);

            assertEquals(StepState.DUE, mustStep.getState());
        }

        @Test
        void completingStep_doesNotSkipAlreadyTerminalCouldSteps() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            StepInstance completedOptional = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("optional-lab")
                    .repeatIndex(0)
                    .state(StepState.COMPLETED)
                    .requiredBehavior("could")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("visit")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(completedOptional, completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(List.of());

            service.completeStep(completedStep, UUID.randomUUID(), "test-src", null);

            assertEquals(StepState.COMPLETED, completedOptional.getState());
        }
    }

    @Nested
    class SchedulerSkipForOptionalSteps {

        @Test
        void overdueToMissed_couldStep_becomesSkipped_noDeviation() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);
            step.setRequiredBehavior("could");

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.SKIPPED, step.getState());
            verify(deviationService, never()).createDeviation(any(), any());
            verify(intelligenceActionEvaluator, never()).evaluateOnDeviation(any(), any());
        }

        @Test
        void overdueToMissed_mustStep_becomesMissed_withDeviation() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);
            step.setRequiredBehavior("must");
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.MISSED)))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());
            verify(deviationService).createDeviation(eq(step), eq(DeviationType.MISSED));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(step, deviation);
        }

        @Test
        void overdueToMissed_nullRequiredBehavior_becomesMissed() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);
            step.setRequiredBehavior(null);
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(deviationService.createDeviation(any(), eq(DeviationType.MISSED)))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());
            verify(deviationService).createDeviation(eq(step), eq(DeviationType.MISSED));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(eq(step), eq(deviation));
        }
    }

    @Nested
    class OrderViolationDetection {

        @Test
        void completingStep_withIncompleteMustPredecessor_createsOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step (vitals-recording) still PENDING
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("vitals-recording")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2))
                    .build();

            // Successor step (chief-complaints) being completed
            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("chief-complaints")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            // chief-complaints has relatedAction pointing to its own predecessor, vitals-recording
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(), null, 1, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("chief-complaints", "Chief Complaints",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("vitals-recording", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            Deviation deviation = Deviation.builder().id(UUID.randomUUID()).build();
            when(deviationService.createDeviation(any(), eq(DeviationType.ORDER_VIOLATION), any()))
                    .thenReturn(new DeviationService.DeviationResult(deviation, true));

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationService).createDeviation(
                    eq(completedStep), eq(DeviationType.ORDER_VIOLATION),
                    argThat(metadata -> {
                        @SuppressWarnings("unchecked")
                        List<String> incomplete = (List<String>) metadata.get("incompletePrerequisites");
                        return incomplete != null && incomplete.contains("vitals-recording");
                    }));
            verify(intelligenceActionEvaluator).evaluateOnDeviation(completedStep, deviation);
        }

        @Test
        void completingStep_withCompletedMustPredecessor_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step already COMPLETED
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("vitals-recording")
                    .repeatIndex(0)
                    .state(StepState.COMPLETED)
                    .requiredBehavior("must")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("chief-complaints")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(), null, 1, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("chief-complaints", "Chief Complaints",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("vitals-recording", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationService, never()).createDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }

        @Test
        void completingStep_withIncompleteCouldPredecessor_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // Predecessor step is optional (could) and still PENDING — not a violation
            StepInstance predecessorStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("history-assessment")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("could")
                    .build();

            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("lab-order")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(predecessorStep, completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            // lab-order's own predecessor, history-assessment, is optional (could) — not a violation
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("history-assessment", "History",
                            List.of(), List.of(), null, 1, "could", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("lab-order", "Lab Order",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("history-assessment", "after-end",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationService, never()).createDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }

        @Test
        void completingFirstStep_noPredecessors_noOrderViolation() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();

            // First step in chain — no predecessors
            StepInstance completedStep = StepInstance.builder()
                    .id(UUID.randomUUID())
                    .protocolInstance(protocolInstance)
                    .actionId("visit-encounter")
                    .repeatIndex(0)
                    .state(StepState.PENDING)
                    .requiredBehavior("must")
                    .dueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                    .overdueDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(10))
                    .build();

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            lenient().when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(completedStep));

            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            // visit-encounter has no relatedAction of its own (it's the root); vitals-recording
            // declares visit-encounter as ITS predecessor, which must not affect visit-encounter's
            // own check
            List<PlanDefinitionParser.StepMetadata> actions = List.of(
                    new PlanDefinitionParser.StepMetadata("visit-encounter", "Visit",
                            List.of(), List.of(), null, 1, "must", List.of(), null),
                    new PlanDefinitionParser.StepMetadata("vitals-recording", "Vitals",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedStepInfo("visit-encounter", "after-start",
                                    BigDecimal.ZERO, "d")),
                            null, 1, "must", List.of(), null));
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(actions);

            service.completeStep(completedStep, UUID.randomUUID(), "test-source", null);

            verify(deviationService, never()).createDeviation(
                    any(), eq(DeviationType.ORDER_VIOLATION), any());
        }
    }

    @Nested
    class BackfillMissingMandatorySteps {

        /**
         * The emr-service-protocol journey: an explicit forward chain, with the consultation
         * sub-steps nested under {@code consultation} and {@code lab-results} under
         * {@code lab-order}. Mandatory ("must"): vitals-recording, consultation, diagnosis.
         */
        private List<PlanDefinitionParser.StepMetadata> emrJourney() {
            return List.of(
                    action("visit-encounter", "vitals-recording", null, null),
                    action("vitals-recording", "consultation", "must", null),
                    action("consultation", "chief-complaints", "must", null),
                    action("chief-complaints", "history-assessment", null, "consultation"),
                    action("history-assessment", "lab-order", "could", "consultation"),
                    action("lab-order", "lab-results", null, "consultation"),
                    action("lab-results", "diagnosis", null, "lab-order"),
                    action("diagnosis", "treatment", "must", "consultation"),
                    action("treatment", "referral", null, "consultation"),
                    action("referral", null, "could", "consultation"));
        }

        private PlanDefinitionParser.StepMetadata action(String id, String nextActionId,
                                                         String requiredBehavior, String parentActionId) {
            return action(id, nextActionId, requiredBehavior, parentActionId, 1);
        }

        private PlanDefinitionParser.StepMetadata action(String id, String nextActionId,
                                                         String requiredBehavior, String parentActionId,
                                                         Integer toleranceDays) {
            List<PlanDefinitionParser.RelatedStepInfo> related = nextActionId == null
                    ? List.of()
                    : List.of(new PlanDefinitionParser.RelatedStepInfo(
                            nextActionId, "after-end", BigDecimal.ZERO, "d"));
            return new PlanDefinitionParser.StepMetadata(id, id, List.of(), related,
                    null, toleranceDays, requiredBehavior, List.of(), parentActionId);
        }

        private void stubGraph(List<PlanDefinitionParser.StepMetadata> steps) {
            PlanDefinition mockPlanDef = mock(PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractSteps(mockPlanDef)).thenReturn(steps);
        }

        private Map<String, StepInstance> capturedStepsExcept(String... actionIds) {
            ArgumentCaptor<StepInstance> captor = ArgumentCaptor.forClass(StepInstance.class);
            verify(stepInstanceRepository, atLeastOnce()).save(captor.capture());
            Set<String> excluded = Set.of(actionIds);
            return captor.getAllValues().stream()
                    .filter(s -> !excluded.contains(s.getActionId()))
                    .collect(Collectors.toMap(StepInstance::getActionId, s -> s, (a, b) -> a));
        }

        @Test
        void stepCompletedWithNoPrecedingRows_backfillsMandatoryStepsAsPending() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

            // Only `treatment` was ever recorded — its predecessors have no step_instance row,
            // so the journey view shows them as "not started" and the scheduler cannot see them.
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepState.PENDING, occurredAt.minusHours(2), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            Map<String, StepInstance> backfilled = capturedStepsExcept("treatment");

            // Mandatory only: history-assessment/referral are `could`, and visit-encounter,
            // chief-complaints, lab-order, lab-results declare no requiredBehavior.
            assertEquals(Set.of("vitals-recording", "consultation", "diagnosis"), backfilled.keySet());
            backfilled.values().forEach(step -> {
                assertEquals(StepState.PENDING, step.getState());
                assertEquals("must", step.getRequiredBehavior());
                assertEquals(0, step.getRepeatIndex());
                // Anchored to the clinical completion time, with tolerance-derived thresholds so
                // the scheduler drives them OVERDUE then MISSED if they are never recorded.
                assertEquals(treatment.getCompletedAt(), step.getDueDate());
                assertEquals(treatment.getCompletedAt().plusDays(1), step.getOverdueDate());
                assertEquals(treatment.getCompletedAt().plusDays(2), step.getMissedDate());
            });
        }

        @Test
        void mandatoryStepWithExistingTerminalRow_notBackfilledAgain() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance missedDiagnosis = buildStepWithProtocol(protocolInstance, "diagnosis",
                    StepState.MISSED, occurredAt.minusDays(5), occurredAt.minusDays(4));
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepState.PENDING, occurredAt.minusHours(2), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(missedDiagnosis, treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            // A mandatory action with any row — here a terminal MISSED one — is left alone.
            assertEquals(Set.of("vitals-recording", "consultation"),
                    capturedStepsExcept("treatment", "diagnosis").keySet());
        }

        @Test
        void inOrderCompletion_noMandatoryGap_backfillsNothing() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance visit = buildStepWithProtocol(protocolInstance, "visit-encounter",
                    StepState.COMPLETED, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepState.PENDING, occurredAt.minusHours(1), occurredAt.plusDays(1));
            vitals.setRequiredBehavior("must");

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(visit, vitals));
            stubGraph(emrJourney());

            service.completeStep(vitals, UUID.randomUUID(), "ebuzima", occurredAt);

            // `consultation` is created by progressive instantiation (vitals-recording's dependent),
            // and nothing else — no mandatory action is missing at this point in the journey.
            assertEquals(Set.of("consultation"), capturedStepsExcept("vitals-recording").keySet());
        }

        @Test
        void completingNestedSubStep_doesNotBackfillMandatoryStepStillAheadInChain() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepState.COMPLETED, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepState.COMPLETED, occurredAt.minusHours(2), occurredAt.minusHours(1));
            StepInstance chiefComplaints = buildStepWithProtocol(protocolInstance, "chief-complaints",
                    StepState.PENDING, occurredAt.minusHours(1), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(vitals, consultation, chiefComplaints));
            stubGraph(emrJourney());

            service.completeStep(chiefComplaints, UUID.randomUUID(), "ebuzima", occurredAt);

            // `diagnosis` is mandatory and shares the completed step's nesting group, but the
            // forward chain has not reached it — backfilling it here would stamp it with this
            // completion's time and flatten the schedule its own relatedAction offsets define.
            // It stays out until progress past it appears (or its own trigger fires), and protocol
            // completion is gated on it regardless. `history-assessment` is `could`, so progressive
            // instantiation skips it too — nothing at all is created.
            assertTrue(capturedStepsExcept("chief-complaints").isEmpty());
        }

        @Test
        void mandatoryStepAheadIsBackfilledOnceProgressPastItAppears() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance vitals = buildStepWithProtocol(protocolInstance, "vitals-recording",
                    StepState.COMPLETED, occurredAt.minusHours(3), occurredAt.minusHours(2));
            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepState.COMPLETED, occurredAt.minusHours(2), occurredAt.minusHours(1));
            StepInstance chiefComplaints = buildStepWithProtocol(protocolInstance, "chief-complaints",
                    StepState.COMPLETED, occurredAt.minusHours(1), occurredAt);
            // `treatment` arrives on its own trigger, skipping over `diagnosis`
            StepInstance treatment = buildStepWithProtocol(protocolInstance, "treatment",
                    StepState.PENDING, occurredAt.minusMinutes(30), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(vitals, consultation, chiefComplaints, treatment));
            stubGraph(emrJourney());

            service.completeStep(treatment, UUID.randomUUID(), "ebuzima", occurredAt);

            // `diagnosis` is now a predecessor of observed progress, so it is genuinely late and
            // gets materialized.
            assertEquals(Set.of("diagnosis"), capturedStepsExcept("treatment").keySet());
        }

        @Test
        void actionWithoutToleranceDays_backfilledWithoutOverdueAndMissedDates() {
            ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            StepInstance consultation = buildStepWithProtocol(protocolInstance, "consultation",
                    StepState.PENDING, occurredAt.minusHours(1), occurredAt.plusDays(1));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(stepInstanceRepository.findByProtocolInstanceId(protocolInstance.getId()))
                    .thenReturn(List.of(consultation));
            stubGraph(List.of(
                    action("vitals-recording", "consultation", "must", null, null),
                    action("consultation", null, "must", null, null)));

            service.completeStep(consultation, UUID.randomUUID(), "ebuzima", occurredAt);

            StepInstance backfilled = capturedStepsExcept("consultation").get("vitals-recording");
            assertNotNull(backfilled);
            assertEquals(StepState.PENDING, backfilled.getState());
            assertEquals(consultation.getCompletedAt(), backfilled.getDueDate());
            // No tolerance-days extension: the scheduler has no threshold to advance it past DUE.
            assertNull(backfilled.getOverdueDate());
            assertNull(backfilled.getMissedDate());
        }
    }

    @Nested
    class ReadOperations {

        @Test
        void findById_existing_returnsStep() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.PENDING, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));

            StepInstance result = service.findById(stepId);
            assertEquals(stepId, result.getId());
        }

        @Test
        void findById_notFound_throwsEntityNotFound() {
            UUID stepId = UUID.randomUUID();
            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> service.findById(stepId));
        }

        @Test
        void findByProtocolInstanceId_delegatesToRepository() {
            UUID instanceId = UUID.randomUUID();
            when(stepInstanceRepository.findByProtocolInstanceId(instanceId)).thenReturn(List.of());

            List<StepInstance> result = service.findByProtocolInstanceId(instanceId);
            assertTrue(result.isEmpty());
            verify(stepInstanceRepository).findByProtocolInstanceId(instanceId);
        }
    }

    // ── Helpers ──

    private ProtocolInstance buildProtocolInstance() {
        return ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .status(ProtocolInstanceStatus.ACTIVE)
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();
    }

    private ProtocolInstance buildProtocolInstanceWithDefinition() {
        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .id(UUID.randomUUID())
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .build();

        return ProtocolInstance.builder()
                .id(UUID.randomUUID())
                .patientId("patient-1")
                .protocolDefinition(protocolDef)
                .protocolCanonical("http://openphc.org/PlanDefinition/anc-high-risk|1.0.0")
                .status(ProtocolInstanceStatus.ACTIVE)
                .steps(new HashSet<>())
                .deviations(new HashSet<>())
                .build();
    }

    private StepInstance buildStep(StepState state, OffsetDateTime dueDate, OffsetDateTime overdueDate) {
        ProtocolInstance protocolInstance = buildProtocolInstanceWithDefinition();
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId("action-1")
                .repeatIndex(0)
                .state(state)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .build();
    }

    private StepInstance buildStepWithProtocol(ProtocolInstance protocolInstance, String actionId,
                                               StepState state, OffsetDateTime dueDate,
                                               OffsetDateTime overdueDate) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .protocolInstance(protocolInstance)
                .actionId(actionId)
                .repeatIndex(0)
                .state(state)
                .dueDate(dueDate)
                .overdueDate(overdueDate)
                .build();
    }
}
