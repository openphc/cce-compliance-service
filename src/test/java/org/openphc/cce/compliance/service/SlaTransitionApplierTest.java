package org.openphc.cce.compliance.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.Deviation;
import org.openphc.cce.common.entity.StepInstance;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.repository.StepInstanceRepository;
import org.openphc.cce.common.deviation.DeviationRecorder;
import org.openphc.cce.common.intelligence.IntelligenceActionEvaluator;
import org.openphc.cce.common.history.StateTransitionHistoryWriter;
import org.openphc.cce.compliance.domain.repository.OnTimeStepFetchRepository;
import org.openphc.cce.compliance.domain.repository.SlaTransitionFetchRepository;
import org.springframework.data.domain.Limit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The applier is the only writer of {@code step_instance.sla_status}, so these tests are the whole
 * specification of how a step's timeliness gets decided. Matcher records {@code completed_at}; every
 * judgement made from it is here.
 */
@ExtendWith(MockitoExtension.class)
class SlaTransitionApplierTest {

    @Mock private SlaTransitionFetchRepository transitionRepository;
    @Mock private OnTimeStepFetchRepository onTimeStepRepository;
    @Mock private StepInstanceRepository stepInstanceRepository;
    @Mock private DeviationRecorder deviationRecorder;
    @Mock private IntelligenceActionEvaluator intelligenceActionEvaluator;
    @Mock private StateTransitionHistoryWriter stateTransitionHistoryWriter;

    private SlaTransitionApplier applier;
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        applier = new SlaTransitionApplier(transitionRepository, onTimeStepRepository, stepInstanceRepository,
                deviationRecorder, intelligenceActionEvaluator, stateTransitionHistoryWriter,
                "test-instance", 100, 3600, new SimpleMeterRegistry());
    }

    // ── the work never arrived ──

    @Nested
    class OutstandingStep {

        @Test
        void dueDateReached_becomesOverdueWithAnOverdueDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            // crossing a deadline says nothing about whether the event arrived
            assertEquals(StepStatus.NOT_STARTED, step.getStepStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
            assertTrue(row.isProcessed());
            assertEquals("test-instance", row.getProcessedBy());
        }

        @Test
        void missedDateReached_mandatory_becomesMissedWithAMissedDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.MISSED);
        }

        @Test
        void missedDateReached_optional_recordsNeitherStatusNorDeviation() {
            // Nothing was required of an optional step, so nothing was breached by its not happening.
            // It keeps the OVERDUE the due date gave it — being late is still a fact about it.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "could", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void everyStatusWriteIsRecordedInHistory() {
            // Without this the time-driven half of a step's timeline is missing from the CDC stream:
            // a step that went overdue and was never completed would show only its creation.
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            verify(stateTransitionHistoryWriter)
                    .recordStepInstanceTransition(eq(step), any(OffsetDateTime.class));
        }
    }

    // ── the work arrived; completed_at decides ──

    @Nested
    class CompletedStep {

        @Test
        void completedBeforeItsDueDate_recordsNoBreachAndLeavesMetToTheSweep() {
            // A kept threshold is not a verdict. The row had no breach to detect, so it is consumed
            // without a status; MET is settled from the step by fetchAndSettleOnTime.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertNull(step.getSlaStatus());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void completedAfterItsDueDate_isOverdueWithADeviation() {
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedBetweenItsThresholds_staysOverdueRatherThanBecomingMet() {
            // The trap in the design: this step beat its missed date, but "did not breach this
            // threshold" only means MET at the due date. Reading it as MET here would relabel a late
            // completion as on time.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusHours(2));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
            assertTrue(row.isProcessed());
        }

        @Test
        void completedAfterItsMissedDate_isMissedWithADeviation() {
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusHours(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.MISSED);
        }

        @Test
        void completedAfterItsMissedDate_optional_recordsNoMissedDeviation() {
            // must-only, on this path as much as the outstanding one: otherwise optional work done
            // late would be penalised while the same work never done at all was not.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "could", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusHours(1));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
        }

        @Test
        void completedAfterItsDueDate_optional_stillTakesAnOverdueDeviation() {
            // The exemption is MISSED-only: optional work can still be reported as running late.
            StepInstance step = step(StepStatus.COMPLETED, null, "could", now.minusMinutes(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedExactlyAtItsThreshold_countsAsABreach() {
            OffsetDateTime threshold = now.minusHours(1);
            StepInstance step = step(StepStatus.COMPLETED, null, "must", threshold);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, threshold);
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void completedWithNoTimestamp_isTreatedAsABreach() {
            // The row is better evidence than a missing timestamp, and letting it pass would hide
            // the gap rather than surface it.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
        }
    }

    // ── the row's schedule, not the step's due date ──

    @Nested
    class WhichSideDecidesWhat {

        @Test
        void overdueIsMeasuredAgainstTheRowsProcessBy() {
            // A breach is the schedule's question. completed_at is past process_by, so the row records
            // OVERDUE — even though the step's own due date, later here, was beaten.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(4));
            step.setDueDate(now.minusHours(1));
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(deviationRecorder).recordDeviation(step, DeviationType.OVERDUE);
        }

        @Test
        void theMissedDateRowIsStillJudgedByItsOwnSchedule() {
            // The missed date is not stored on the step, so that row's process_by is the threshold. A
            // due_date far in the past must not drag the missed-date verdict with it.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            StepSlaStateTransition row = row(step, SlaTransitionType.MISSED_DATE_REACHED, now.minusHours(1));
            step.setDueDate(now.minusDays(30));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            // Completed before the missed date: not written off, and no verdict of its own to give.
            verify(deviationRecorder, never()).recordDeviation(any(), any());
            assertTrue(row.isProcessed());
        }
    }

    @Nested
    class OnTimeSweep {

        @Test
        void aStepThatBeatItsDueDateIsRecordedMet() {
            // Driven off step_instance: no transition row is fetched, and none is needed.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            step.setDueDate(now.minusHours(1));
            when(onTimeStepRepository.fetchOnTimeSteps(any())).thenReturn(List.of(step));

            int count = applier.fetchAndSettleOnTime(new ArrayList<>());

            assertEquals(1, count);
            assertEquals(SlaStatus.MET, step.getSlaStatus());
            verify(transitionRepository, never()).fetchDueTransitions(any(), any());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
        }

        @Test
        void theTransitionIsRecordedInHistory() {
            // Why this is a row-at-a-time sweep rather than one bulk UPDATE: step_instance_history has
            // to carry every sla_status transition, and a set update would skip it.
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            step.setDueDate(now.minusHours(1));
            when(onTimeStepRepository.fetchOnTimeSteps(any())).thenReturn(List.of(step));

            applier.fetchAndSettleOnTime(new ArrayList<>());

            verify(stateTransitionHistoryWriter).recordStepInstanceTransition(eq(step), any());
        }

        @Test
        void anAlreadySettledStepIsRefusedRatherThanRelabelled() {
            // The query excludes these; this is the belt to that braces. Timeliness is settled once
            // decided, and a late completion must not be turned into an on-time one.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusHours(3));
            step.setDueDate(now.minusHours(1));
            when(onTimeStepRepository.fetchOnTimeSteps(any())).thenReturn(List.of(step));

            applier.fetchAndSettleOnTime(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(stateTransitionHistoryWriter, never()).recordStepInstanceTransition(any(), any());
        }

        @Test
        void theFetchedIdsAreReportedToTheCaller() {
            StepInstance step = step(StepStatus.COMPLETED, null, "must", now.minusHours(3));
            step.setDueDate(now.minusHours(1));
            when(onTimeStepRepository.fetchOnTimeSteps(any())).thenReturn(List.of(step));
            List<UUID> fetched = new ArrayList<>();

            applier.fetchAndSettleOnTime(fetched);

            assertEquals(List.of(step.getId()), fetched);
        }
    }

    @Nested
    class BatchCapacity {

        @Test
        void theOnlyFetchAsksForTheConfiguredBatchSize() {
            // One query, so the whole batch is its to fill — there is no room left over for a second.
            SlaTransitionApplier smallBatch = applierWithBatchSize(3);
            when(transitionRepository.fetchDueTransitions(any(), any())).thenReturn(List.of());

            smallBatch.fetchAndApply(new ArrayList<>());

            ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
            verify(transitionRepository).fetchDueTransitions(any(), limit.capture());
            assertEquals(3, limit.getValue().max());
        }

        private SlaTransitionApplier applierWithBatchSize(int batchSize) {
            return new SlaTransitionApplier(transitionRepository, onTimeStepRepository, stepInstanceRepository,
                    deviationRecorder, intelligenceActionEvaluator, stateTransitionHistoryWriter,
                    "test-instance", batchSize, 3600, new SimpleMeterRegistry());
        }
    }

    // ── the forward-only rule ──

    @Nested
    class OutOfOrderApplication {

        @Test
        void overdueDoesNotOverwriteMissed() {
            // Rows are fetched oldest-deadline-first, but a retried batch can still land out of
            // order. Re-applying the due date must not walk a written-off step back to OVERDUE.
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.MISSED, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(2));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.MISSED, step.getSlaStatus());
            verify(stepInstanceRepository, never()).save(any());
            assertTrue(row.isProcessed());
        }

        @Test
        void metIsNotWrittenOverAnExistingJudgement() {
            // MET is written only from null. A step already found OVERDUE cannot be relabelled as
            // having been on time, however its rows are ordered.
            StepInstance step = step(StepStatus.COMPLETED, SlaStatus.OVERDUE, "must", now.minusDays(5));
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(1));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(SlaStatus.OVERDUE, step.getSlaStatus());
            verify(stepInstanceRepository, never()).save(any());
        }

        @Test
        void reappliedBreachDoesNotRaiseASecondDeviation() {
            StepInstance step = step(StepStatus.NOT_STARTED, SlaStatus.OVERDUE, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusHours(2));
            fetch(row, step);

            applier.fetchAndApply(new ArrayList<>());

            verify(deviationRecorder, never()).recordDeviation(any(), any());
        }
    }

    @Nested
    class Retry {

        @Test
        void backOff_defersRowsExponentiallyInTheAttemptCount() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(3);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertTrue(row.getNextAttemptAt().isAfter(before));
            assertEquals(4, row.getAttempts());
        }

        @Test
        void backOff_isCappedSoABrokenRowIsStillRetriedOccasionally() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(40);
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertFalse(row.getNextAttemptAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3601)));
        }

        @Test
        void backOff_leavesAnAlreadyProcessedRowAlone() {
            StepSlaStateTransition row = row(step(StepStatus.NOT_STARTED, null, "must", null),
                    SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setProcessed(true);
            OffsetDateTime before = row.getNextAttemptAt();
            when(transitionRepository.findAllById(List.of(row.getId()))).thenReturn(List.of(row));

            applier.backOff(List.of(row.getId()));

            assertEquals(before, row.getNextAttemptAt());
            verify(transitionRepository, never()).save(row);
        }
    }

    @Nested
    class Bookkeeping {

        @Test
        void duplicateDeviation_doesNotRepublishIntelligence() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);
            when(deviationRecorder.recordDeviation(any(), any())).thenReturn(
                    new DeviationRecorder.DeviationResult(
                            Deviation.builder().id(UUID.randomUUID()).build(), false));

            applier.fetchAndApply(new ArrayList<>());

            verify(intelligenceActionEvaluator, never()).evaluateOnDeviation(any(), any());
        }

        @Test
        void missingStep_consumesTheRowRatherThanRetryingForever() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            when(transitionRepository.fetchDueTransitions(any(), any())).thenReturn(List.of(row));
            when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.empty());

            applier.fetchAndApply(new ArrayList<>());

            assertTrue(row.isProcessed());
            verify(deviationRecorder, never()).recordDeviation(any(), any());
        }

        @Test
        void fetchReportsEveryRowItTook_soAFailedBatchCanBeBackedOff() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            fetch(row, step);
            freshDeviation();
            List<UUID> fetched = new ArrayList<>();

            int count = applier.fetchAndApply(fetched);

            assertEquals(1, count);
            assertEquals(List.of(row.getId()), fetched);
        }

        @Test
        void repeatedFailuresAreEscalatedOnFetch() {
            StepInstance step = step(StepStatus.NOT_STARTED, null, "must", null);
            StepSlaStateTransition row = row(step, SlaTransitionType.DUE_DATE_REACHED, now.minusMinutes(1));
            row.setAttempts(9);
            fetch(row, step);
            freshDeviation();

            applier.fetchAndApply(new ArrayList<>());

            assertEquals(10, row.getAttempts());
        }
    }

    private void fetch(StepSlaStateTransition row, StepInstance step) {
        when(transitionRepository.fetchDueTransitions(any(), any())).thenReturn(List.of(row));
        when(stepInstanceRepository.findById(step.getId())).thenReturn(Optional.of(step));
    }

    private void freshDeviation() {
        when(deviationRecorder.recordDeviation(any(), any())).thenReturn(
                new DeviationRecorder.DeviationResult(
                        Deviation.builder().id(UUID.randomUUID()).build(), true));
    }

    private StepInstance step(StepStatus stepStatus, SlaStatus slaStatus,
                             String requiredBehavior, OffsetDateTime completedAt) {
        return StepInstance.builder()
                .id(UUID.randomUUID())
                .actionId("anc-visit-1")
                .repeatIndex(0)
                .stepStatus(stepStatus)
                .slaStatus(slaStatus)
                .requiredBehavior(requiredBehavior)
                .completedAt(completedAt)
                .build();
    }

    private StepSlaStateTransition row(StepInstance step, SlaTransitionType type,
                                       OffsetDateTime processBy) {
        if (type == SlaTransitionType.DUE_DATE_REACHED) {
            // What the Matcher does: the deadline is written on the step and scheduled on the row from
            // the same value, in one transaction. Tests that need them to differ set the step's own.
            step.setDueDate(processBy);
        }
        return StepSlaStateTransition.builder()
                .id(UUID.randomUUID())
                .stepInstanceId(step.getId())
                .transitionType(type)
                .processBy(processBy)
                .nextAttemptAt(processBy)
                .build();
    }
}
