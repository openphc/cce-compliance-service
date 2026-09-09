package org.openphc.cce.compliance.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Fetches due {@code step_sla_state_transition} rows and applies them.
 *
 * <p>The Matcher Service writes one row per threshold a step can cross and never touches it again.
 * Everything after that is owned here: deciding which rows are due, writing
 * {@code step_instance.sla_status}, raising the {@code OVERDUE} / {@code MISSED} deviation, recording
 * the transition in {@code step_instance_history}, and marking the row processed. There is no Kafka hop
 * and no HTTP call between the two services — they meet on this one table.
 *
 * <p>Fetch and apply share a transaction. The row lock taken by {@code FOR UPDATE SKIP LOCKED} is what
 * reserves the row, so concurrent instances drain disjoint sets with no lease table and no leader election, and a
 * deviation can never be recorded without the row being marked processed in the same commit.
 *
 * <p>Separate bean from {@link SlaTransitionEvaluator}, which drives the polling loop. Not cosmetic:
 * Spring's transaction proxy is bypassed by self-invocation, so a driver calling its own
 * {@code @Transactional} method would silently run it without a transaction.
 *
 * <h2>This service is the only writer of sla_status</h2>
 * Matcher records <em>that</em> a step was completed and when; it never judges whether that was timely.
 * So there is no rule here about not overwriting what Matcher decided — it decided nothing. A step's
 * {@code sla_status} is null until a threshold falls due and this service judges it.
 *
 * <p>The judgement compares {@code step_instance.completed_at} — the clinical occurrence time of the
 * completing event — against the threshold the row stands for. The wall clock never enters into it:
 * what a deadline <em>means</em> depends only on whether the work had happened by then.
 *
 * <p>A breach is all a transition row decides. {@code OVERDUE} and {@code MISSED} are measured against
 * its {@code process_by}, which is what a schedule exists to detect and what the row carries.
 *
 * <p>{@code MET} is not decided here at all. Whether work was recorded <em>on time</em> is a question
 * about the step, answerable from {@code step_instance.completed_at} against
 * {@code step_instance.due_date} with no schedule to consult, so {@link #fetchAndSettleOnTime} sweeps
 * {@code step_instance} for it directly. A row whose threshold was kept therefore records nothing and is
 * simply consumed.
 *
 * <p>A row is fetched for exactly one reason: its schedule has come round, and the work must be judged
 * against its threshold ({@code fetchDueTransitions}). Nothing pulls a step's remaining rows forward
 * because the step completed or was judged — a settled step keeps its unspent schedule until those
 * dates arrive, and each row is consumed then, recording nothing.
 *
 * <table border="1">
 *   <caption>Behaviour by threshold and step state</caption>
 *   <tr><th>Row</th><th>Step state when applied</th><th>Action</th></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>not completed</td>
 *       <td>{@code OVERDUE} + {@code OVERDUE} deviation</td></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at >= process_by}</td>
 *       <td>{@code OVERDUE} + {@code OVERDUE} deviation — recorded, but late</td></tr>
 *   <tr><td>{@code DUE_DATE_REACHED}</td><td>{@code completed_at < process_by}</td>
 *       <td>consume — no breach; {@code MET} is settled from the step, not from this row</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>not completed</td>
 *       <td>{@code MISSED} + {@code MISSED} deviation ({@code must} only)</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>{@code completed_at >= process_by}</td>
 *       <td>{@code MISSED} + {@code MISSED} deviation ({@code must} only)</td></tr>
 *   <tr><td>{@code MISSED_DATE_REACHED}</td><td>{@code completed_at < process_by}</td>
 *       <td>consume — this threshold was not breached, and the due-date row already had its say</td></tr>
 * </table>
 *
 * <p>The last row is the one worth being careful about: a step completed <em>between</em> its two
 * thresholds did not breach the missed date, but it is not {@code MET} either — it is the {@code OVERDUE}
 * the due-date row made it. "Did not breach this threshold" and "met its SLA" are only the same thing at
 * the due date, which is why {@code MET} is written on that row alone.
 *
 * <p>{@code step_status} is never written here. Crossing a deadline says nothing about whether the event
 * arrived.
 *
 * <p>Nothing in the table turns on <em>when</em> a row is applied, which is what makes fetching a
 * completed step's rows early safe: the same columns decide the outcome whether the row is applied at
 * its scheduled time or the moment the completion is seen. Reading the due date off the step rather than
 * off the schedule strengthens that — the value the verdict turns on is one the sweep never rewrites.
 */
@Service
public class SlaTransitionApplier {

    private static final Logger log = LoggerFactory.getLogger(SlaTransitionApplier.class);

    /** Past this many attempts a row is logged as an error every cycle rather than failing quietly. */
    private static final int ATTEMPTS_BEFORE_ALERT = 5;

    private final SlaTransitionFetchRepository transitionRepository;
    private final OnTimeStepFetchRepository onTimeStepRepository;
    private final StepInstanceRepository stepInstanceRepository;
    private final DeviationRecorder deviationRecorder;
    private final IntelligenceActionEvaluator intelligenceActionEvaluator;
    private final StateTransitionHistoryWriter stateTransitionHistoryWriter;
    private final String instanceId;
    private final int batchSize;
    private final Duration maxBackoff;
    private final Counter appliedCounter;
    private final Counter consumedCounter;

    public SlaTransitionApplier(SlaTransitionFetchRepository transitionRepository,
                                OnTimeStepFetchRepository onTimeStepRepository,
                                StepInstanceRepository stepInstanceRepository,
                                DeviationRecorder deviationRecorder,
                                IntelligenceActionEvaluator intelligenceActionEvaluator,
                                StateTransitionHistoryWriter stateTransitionHistoryWriter,
                                @Value("${cce.sla.instance-id:${HOSTNAME:local}}") String instanceId,
                                @Value("${cce.sla.batch-size:100}") int batchSize,
                                @Value("${cce.sla.max-backoff-seconds:3600}") long maxBackoffSeconds,
                                MeterRegistry meterRegistry) {
        this.transitionRepository = transitionRepository;
        this.onTimeStepRepository = onTimeStepRepository;
        this.stepInstanceRepository = stepInstanceRepository;
        this.deviationRecorder = deviationRecorder;
        this.intelligenceActionEvaluator = intelligenceActionEvaluator;
        this.stateTransitionHistoryWriter = stateTransitionHistoryWriter;
        this.instanceId = instanceId;
        this.batchSize = batchSize;
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
        this.appliedCounter = Counter.builder("cce.sla.transitions.applied")
                .description("SLA transitions that wrote a step's sla_status")
                .register(meterRegistry);
        this.consumedCounter = Counter.builder("cce.sla.transitions.consumed")
                .description("SLA transitions closed without writing a status or a deviation")
                .register(meterRegistry);
    }

    /**
     * Fetch and apply one batch of due transitions.
     *
     * <p>One query, one gate. {@code next_attempt_at} decides eligibility and nothing else does; it then
     * plays no part in the judgement, which reads {@code transition_type} and {@code process_by} from the
     * row — both immutable — and {@code step_status}, {@code completed_at}, {@code sla_status} and
     * {@code required_behavior} from the step. So <em>when</em> a row is applied cannot change what it
     * decides.
     *
     * @param fetched populated with the id of every row fetched, so the caller can back them off if the
     *                transaction rolls back — the list is plain memory and survives the rollback
     * @return how many rows were fetched
     */
    @Transactional
    public int fetchAndApply(List<UUID> fetched) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<StepSlaStateTransition> batch = transitionRepository.fetchDueTransitions(now, Limit.of(batchSize));

        for (StepSlaStateTransition row : batch) {
            fetched.add(row.getId());
            row.setAttempts(row.getAttempts() + 1);
            if (row.getAttempts() > ATTEMPTS_BEFORE_ALERT) {
                log.error("SLA transition {} for step {} has now been attempted {} times",
                        row.getId(), row.getStepInstanceId(), row.getAttempts());
            }
            applyRow(row);
        }
        return batch.size();
    }

    private void applyRow(StepSlaStateTransition row) {
        StepInstance step = stepInstanceRepository.findById(row.getStepInstanceId()).orElse(null);
        if (step == null) {
            // The step is gone, so there is no schedule left to honour. Close the row rather than
            // retrying something that can never succeed.
            log.warn("SLA transition {} references step {} which no longer exists — consuming",
                    row.getId(), row.getStepInstanceId());
            markProcessed(row);
            return;
        }

        if (breachedThreshold(row, step)) {
            applyBreach(row, step);
        } else {
            applyKeptDeadline(row, step);
        }
        markProcessed(row);
    }

    /**
     * Was the work still unrecorded when this threshold fell?
     *
     * <p>A step not completed at all has plainly breached it. A completed one is judged on its
     * {@code completed_at}: at or after the threshold is a breach, before it is not. A completed step
     * with no {@code completed_at} is treated as a breach — the row is the better evidence than a
     * missing timestamp, and silently letting it pass would hide the gap.
     */
    private boolean breachedThreshold(StepSlaStateTransition row, StepInstance step) {
        if (step.getStepStatus() != StepStatus.COMPLETED) {
            return true;
        }
        OffsetDateTime completedAt = step.getCompletedAt();
        return completedAt == null || !completedAt.isBefore(row.getProcessBy());
    }

    /** The deadline was not met: advance the SLA and record the deviation. */
    private void applyBreach(StepSlaStateTransition row, StepInstance step) {
        if (isOptionalMiss(row, step)) {
            // Nothing was required of an optional step, so nothing was breached by its not happening —
            // and its sla_status stays whatever the due date made it.
            consumedCounter.increment();
            log.debug("Step {} is optional — transition {} records no MISSED status or deviation",
                    step.getId(), row.getId());
            return;
        }

        if (!writeSlaStatus(step, row.getTransitionType().breachStatus())) {
            // Already at or past this outcome: a re-fetched row, or rows applied out of order.
            consumedCounter.increment();
            return;
        }

        raiseDeviationFor(row, step);
    }

    /**
     * The threshold was kept, so this row has nothing to record. Keeping a threshold is not a verdict
     * on timeliness: {@code MET} is a statement about the step, settled from {@code step_instance.due_date}
     * by {@link #fetchAndSettleOnTime}, and beating the missed date says nothing more than that the step
     * was not written off.
     *
     * <p>So the row is consumed either way. A step that was on time has been recorded as such already,
     * or will be on the next sweep.
     */
    private void applyKeptDeadline(StepSlaStateTransition row, StepInstance step) {
        consumedCounter.increment();
        log.debug("Step {} kept its {} threshold of {} — transition consumed",
                step.getId(), row.getTransitionType(), row.getProcessBy());
    }

    /**
     * Settle a batch of steps that beat their due date as {@code MET}.
     *
     * <p>Reads {@code step_instance} rather than the schedule. Whether work was recorded on time is a
     * question about the step, answerable from {@code completed_at} against {@code due_date}, so no
     * transition row is fetched and none is needed: a {@code DUE_DATE_REACHED} row exists to detect a
     * breach, and there is no breach here to detect.
     *
     * <p>The step's own {@code sla_status} is the idempotency record. A step is fetched only while that
     * is null, and writing {@code MET} takes it out of the set for good — so there is nothing to mark
     * processed and no attempt count to keep. A step already {@code OVERDUE} was never in the set, and
     * {@link #writeSlaStatus} would refuse the write regardless: timeliness is settled once decided.
     *
     * <p>Per row rather than one bulk {@code UPDATE}, because {@link #writeSlaStatus} also appends to
     * {@code step_instance_history}, which has to hold every {@code sla_status} transition. A set update
     * would leave a gap in the history exactly where a step went on time.
     *
     * <p>The step's pending {@code DUE_DATE_REACHED} row is left alone: it is fetched when its schedule
     * comes round and consumed then, finding the step already settled. That is also what keeps it out of
     * the backlog gauge.
     *
     * @param fetched receives the ids fetched, so a caller can report them after a rollback
     * @return how many steps were fetched
     */
    @Transactional
    public int fetchAndSettleOnTime(List<UUID> fetched) {
        List<StepInstance> batch = onTimeStepRepository.fetchOnTimeSteps(Limit.of(batchSize));
        for (StepInstance step : batch) {
            fetched.add(step.getId());
            if (writeSlaStatus(step, SlaStatus.MET)) {
                log.debug("Step {} beat its due date of {} — recorded MET",
                        step.getId(), step.getDueDate());
            } else {
                consumedCounter.increment();
            }
        }
        return batch.size();
    }

    /**
     * Write {@code sla_status}, recording the transition in history, unless the step is already at a
     * status this one must not overwrite.
     *
     * <p>Forward-only. {@code MET} and {@code MISSED} are settled outcomes, and {@code OVERDUE} must
     * never replace {@code MISSED} — which is what would happen if the two rows for a step were applied
     * out of order after a retry. {@code MET} is written only from null, so a step already found
     * {@code OVERDUE} cannot be relabelled as having been on time.
     *
     * @return whether the status was written
     */
    private boolean writeSlaStatus(StepInstance step, SlaStatus target) {
        SlaStatus current = step.getSlaStatus();
        boolean allowed = target == SlaStatus.MET
                ? current == null
                : rank(target) > rank(current);
        if (!allowed) {
            log.debug("Step {} is already {} — not writing {}", step.getId(), current, target);
            return false;
        }

        step.setSlaStatus(target);
        stepInstanceRepository.save(step);
        stateTransitionHistoryWriter.recordStepInstanceTransition(
                step, OffsetDateTime.now(ZoneOffset.UTC));
        appliedCounter.increment();

        log.info("Step {} (actionId={}) SLA {} -> {}",
                step.getId(), step.getActionId(), current, target);
        return true;
    }

    /** Ordering for the forward-only rule. Null is "not yet judged", so it precedes every outcome. */
    private static int rank(SlaStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case OVERDUE -> 1;
            case MISSED, MET -> 2;
        };
    }

    /**
     * Whether this row is an optional step's missed threshold.
     *
     * <p>A {@code MISSED} deviation is {@code must}-only, so an optional step neither takes the status
     * nor the deviation. An {@code OVERDUE} carries no such exemption: optional work can still be
     * reported as running late.
     */
    private boolean isOptionalMiss(StepSlaStateTransition row, StepInstance step) {
        return row.getTransitionType() == SlaTransitionType.MISSED_DATE_REACHED
                && "could".equals(step.getRequiredBehavior());
    }

    /**
     * The deviation a breach produces: the due date an {@code OVERDUE}, the missed date a
     * {@code MISSED}. Intelligence is evaluated only for a freshly created deviation, so a re-fetched
     * row cannot publish the same intelligence event twice.
     */
    private void raiseDeviationFor(StepSlaStateTransition row, StepInstance step) {
        DeviationType type = row.getTransitionType() == SlaTransitionType.DUE_DATE_REACHED
                ? DeviationType.OVERDUE
                : DeviationType.MISSED;

        DeviationRecorder.DeviationResult result = deviationRecorder.recordDeviation(step, type);
        if (result.created()) {
            intelligenceActionEvaluator.evaluateOnDeviation(step, result.deviation());
        }
    }

    private void markProcessed(StepSlaStateTransition row) {
        row.setProcessed(true);
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setProcessedBy(instanceId);
        transitionRepository.save(row);
    }

    /**
     * Defer the given rows after a failed batch, so a broken row backs off instead of being retried on
     * every cycle. Runs in its own transaction because the batch it belongs to has just rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void backOff(List<UUID> transitionIds) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (StepSlaStateTransition row : transitionRepository.findAllById(transitionIds)) {
            if (row.isProcessed()) {
                continue;
            }
            // Exponential in the attempt count, capped so a permanently broken row is still retried
            // occasionally rather than hammering the database.
            long seconds = Math.min(maxBackoff.getSeconds(),
                    (long) Math.pow(2, Math.min(row.getAttempts(), 20)));
            row.setAttempts(row.getAttempts() + 1);
            row.setNextAttemptAt(now.plusSeconds(seconds));
            transitionRepository.save(row);
        }
    }
}
