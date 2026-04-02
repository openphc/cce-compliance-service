package org.openphc.cce.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import java.util.Optional;
import java.util.UUID;

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
    private ProtocolInstanceService protocolInstanceService;

    @Mock
    private DeviationService deviationService;

    @Mock
    private AuditService auditService;

    private StepInstanceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new StepInstanceService(stepInstanceRepository,
                planDefinitionParser, protocolInstanceService, deviationService, auditService);
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
        }
    }

    @Nested
    class CompleteStep {

        @Test
        void pendingStep_completedBeforeDue_statusEarly() {
            OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
            StepInstance step = buildStep(StepState.PENDING, dueDate, dueDate.plusDays(3));

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            UUID eventId = UUID.randomUUID();
            service.completeStep(step, eventId, "test-source");

            assertEquals(StepState.COMPLETED, step.getState());
            assertNotNull(step.getCompletedAt());
            assertEquals(eventId, step.getMatchedEventId());
            assertEquals("test-source", step.getCompletedBySource());
            assertEquals(CompletionStatus.EARLY, step.getCompletionStatus());

            verify(auditService).audit(eq("COMPLIANCE"), eq("STEP_COMPLETED"),
                    eq("system"), eq("StepInstance"), anyString(), anyMap());
            verify(protocolInstanceService).checkAndCompleteProtocol(step.getProtocolInstance().getId());
        }

        @Test
        void dueStep_completedOnTime_statusOnTime() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
            OffsetDateTime futureOverdue = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5);
            StepInstance step = buildStep(StepState.DUE, pastDue, futureOverdue);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.completeStep(step, UUID.randomUUID(), "test-source");

            assertEquals(StepState.COMPLETED, step.getState());
            assertEquals(CompletionStatus.ON_TIME, step.getCompletionStatus());
        }

        @Test
        void overdueStep_completedLate() {
            OffsetDateTime pastDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
            OffsetDateTime pastOverdue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
            StepInstance step = buildStep(StepState.OVERDUE, pastDue, pastOverdue);

            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.completeStep(step, UUID.randomUUID(), "test-source");

            assertEquals(StepState.COMPLETED, step.getState());
            assertEquals(CompletionStatus.LATE, step.getCompletionStatus());
        }

        @Test
        void terminalState_throwsIllegalState() {
            StepInstance step = buildStep(StepState.COMPLETED, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.completeStep(step, UUID.randomUUID(), "src"));
        }

        @Test
        void missedState_throwsIllegalState() {
            StepInstance step = buildStep(StepState.MISSED, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.completeStep(step, UUID.randomUUID(), "src"));
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

            // Mock parser to return action metadata with relatedActions
            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.ActionMetadata> actions = List.of(
                    new PlanDefinitionParser.ActionMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedActionInfo("bp-check", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, null, "must"),
                    new PlanDefinitionParser.ActionMetadata("bp-check", "BP Check",
                            List.of(), List.of(), null, 3, "must"));
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source");

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

            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            List<PlanDefinitionParser.ActionMetadata> actions = List.of(
                    new PlanDefinitionParser.ActionMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedActionInfo("bp-check", "after-start",
                                    BigDecimal.valueOf(14), "d")),
                            null, null, "must"),
                    new PlanDefinitionParser.ActionMetadata("bp-check", "BP Check",
                            List.of(), List.of(), null, 3, "must"));
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source");

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

            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);

            // Target action has timing: count=3, period=7 days
            PlanDefinitionParser.TimingInfo timing = new PlanDefinitionParser.TimingInfo(
                    3, 1, BigDecimal.valueOf(7), "d");

            List<PlanDefinitionParser.ActionMetadata> actions = List.of(
                    new PlanDefinitionParser.ActionMetadata("initial-enrollment", "Enrollment",
                            List.of(), List.of(
                            new PlanDefinitionParser.RelatedActionInfo("bp-check", "after-end",
                                    BigDecimal.valueOf(7), "d")),
                            null, null, "must"),
                    new PlanDefinitionParser.ActionMetadata("bp-check", "BP Check",
                            List.of(), List.of(), timing, 3, "must"));
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(actions);

            service.completeStep(step, UUID.randomUUID(), "test-source");

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
            verify(deviationService, never()).recordDeviation(any(), any(), any(), anyMap());
        }

        @Test
        void dueToOverdue_createsDeviation() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.DUE, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("DUE_TO_OVERDUE")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.OVERDUE, step.getState());

            verify(deviationService).recordDeviation(
                    eq(step.getProtocolInstance()), eq(step), eq(DeviationType.OVERDUE),
                    any());
        }

        @Test
        void overdueToMissed_createsDeviationAndChecksProtocol() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());

            verify(deviationService).recordDeviation(
                    eq(step.getProtocolInstance()), eq(step), eq(DeviationType.MISSED),
                    any());

            verify(protocolInstanceService).checkAndCompleteProtocol(step.getProtocolInstance().getId());
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
        void completingStep_autoSkipsPrecedingCouldSteps() {
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
            when(stepInstanceRepository.findByProtocolInstanceIdAndRequiredBehaviorAndStateIn(
                    eq(protocolInstance.getId()), eq("could"), anyCollection()))
                    .thenReturn(List.of(optionalStep));

            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of());

            service.completeStep(completedStep, UUID.randomUUID(), "test-src");

            assertEquals(StepState.SKIPPED, optionalStep.getState());
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
            // No "could" steps exist, so query returns empty
            when(stepInstanceRepository.findByProtocolInstanceIdAndRequiredBehaviorAndStateIn(
                    eq(protocolInstance.getId()), eq("could"), anyCollection()))
                    .thenReturn(List.of());

            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of());

            service.completeStep(completedStep, UUID.randomUUID(), "test-src");

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
            // Already-terminal "could" steps are not in ACTIONABLE_STATES, so query returns empty
            when(stepInstanceRepository.findByProtocolInstanceIdAndRequiredBehaviorAndStateIn(
                    eq(protocolInstance.getId()), eq("could"), anyCollection()))
                    .thenReturn(List.of());

            var mockPlanDef = mock(org.hl7.fhir.r4.model.PlanDefinition.class);
            when(planDefinitionParser.parse(anyString())).thenReturn(mockPlanDef);
            when(planDefinitionParser.extractActions(mockPlanDef)).thenReturn(List.of());

            service.completeStep(completedStep, UUID.randomUUID(), "test-src");

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
            verify(deviationService, never()).recordDeviation(any(), any(), any(), any());
            verify(protocolInstanceService).checkAndCompleteProtocol(step.getProtocolInstance().getId());
        }

        @Test
        void overdueToMissed_mustStep_becomesMissed_withDeviation() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);
            step.setRequiredBehavior("must");
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());
            verify(deviationService).recordDeviation(eq(step.getProtocolInstance()), eq(step),
                    eq(DeviationType.MISSED), any());
        }

        @Test
        void overdueToMissed_nullRequiredBehavior_becomesMissed() {
            UUID stepId = UUID.randomUUID();
            StepInstance step = buildStep(StepState.OVERDUE, null, null);
            step.setId(stepId);
            step.setRequiredBehavior(null);
            step.setMissedDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

            when(stepInstanceRepository.findById(stepId)).thenReturn(Optional.of(step));
            when(stepInstanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SchedulerTriggerMessage trigger = SchedulerTriggerMessage.builder()
                    .stepInstanceId(stepId)
                    .transitionType("OVERDUE_TO_MISSED")
                    .triggeredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            service.applySchedulerTransition(trigger);

            assertEquals(StepState.MISSED, step.getState());
            verify(deviationService).recordDeviation(any(), any(), eq(DeviationType.MISSED), any());
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
