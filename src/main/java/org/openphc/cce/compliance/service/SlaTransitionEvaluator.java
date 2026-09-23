package org.openphc.cce.compliance.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drives SLA transition evaluation: polls for due rows and hands each batch to
 * {@link SlaTransitionApplier}.
 *
 * <p>Holds no transaction of its own. Batches are drained until one comes back short, so a backlog that
 * built up while the service was down is cleared in one cycle rather than one batch per interval, while a
 * steady state costs a single empty fetch query per interval.
 *
 * <p>Safe to run on every instance concurrently — the applier's {@code FOR UPDATE SKIP LOCKED} fetch is
 * what keeps them off each other's rows.
 */
@Service
public class SlaTransitionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SlaTransitionEvaluator.class);

    /** Stops a pathological backlog or a fetch that never drains from monopolising a cycle. */
    private static final int MAX_BATCHES_PER_CYCLE = 100;

    private final SlaTransitionApplier applier;
    private final int batchSize;
    private final Counter cycleCounter;
    private final Counter failedBatchCounter;

    public SlaTransitionEvaluator(SlaTransitionApplier applier,
                                  @Value("${cce.sla.batch-size:100}") int batchSize,
                                  MeterRegistry meterRegistry) {
        this.applier = applier;
        this.batchSize = batchSize;
        this.cycleCounter = Counter.builder("cce.sla.evaluator.cycles")
                .description("SLA evaluation cycles run")
                .register(meterRegistry);
        this.failedBatchCounter = Counter.builder("cce.sla.evaluator.batches.failed")
                .description("SLA evaluation batches that rolled back and were backed off")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cce.sla.poll-interval-ms:5000}")
    public void poll() {
        try {
            int applied = evaluateDue();
            if (applied > 0) {
                log.info("SLA evaluation cycle applied {} transition(s)", applied);
            }
        } catch (RuntimeException e) {
            // Never let a cycle's failure kill the scheduler thread.
            log.error("SLA evaluation cycle failed", e);
        }

        // Independently of the transition sweep, and after it: a breach is the more pressing news, and a
        // failure there must not stop on-time work being recorded. Its own try/catch for the same reason.
        try {
            int settled = settleOnTime();
            if (settled > 0) {
                log.info("SLA evaluation cycle recorded {} step(s) as MET", settled);
            }
        } catch (RuntimeException e) {
            log.error("On-time settlement cycle failed", e);
        }
    }

    /**
     * Drain the steps that beat their due date and have not been told so.
     *
     * <p>Driven off {@code step_instance}, not the schedule: {@code MET} is a statement about the step,
     * and asking it needs only the step's own {@code completed_at} and {@code due_date}. That is what
     * lets an early completion be recorded on the next sweep instead of waiting for a deadline that
     * could be weeks away.
     *
     * <p>Batches drain within the cycle, as the transition sweep does. There is no back-off path: a step
     * is fetched only while its {@code sla_status} is null, so a batch that rolls back leaves it in the
     * set and the next cycle picks it up. Nothing was marked, so nothing needs unmarking — the absence
     * of per-row retry state is the point of driving off the step.
     *
     * @return how many steps were recorded as {@code MET}
     */
    public int settleOnTime() {
        int total = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_CYCLE; batch++) {
            int count = applier.fetchAndSettleOnTime(new ArrayList<>());
            total += count;
            if (count < batchSize) {
                return total;
            }
        }

        log.warn("On-time settlement stopped at the {}-batch cycle limit — backlog may still be draining",
                MAX_BATCHES_PER_CYCLE);
        return total;
    }

    /**
     * Drain the due backlog.
     *
     * @return how many rows were fetched across all batches
     */
    public int evaluateDue() {
        cycleCounter.increment();
        int total = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_CYCLE; batch++) {
            List<UUID> fetched = new ArrayList<>();
            int count;
            try {
                count = applier.fetchAndApply(fetched);
            } catch (RuntimeException e) {
                // The batch rolled back, so nothing was marked processed and no deviation was written.
                // Back the fetched rows off in a fresh transaction so they are retried later rather than
                // on every cycle, then stop: whatever broke is likely to break the next batch too.
                failedBatchCounter.increment();
                log.error("SLA batch of {} row(s) failed and was rolled back — backing off",
                        fetched.size(), e);
                if (!fetched.isEmpty()) {
                    applier.backOff(fetched);
                }
                return total;
            }

            total += count;
            if (count < batchSize) {
                return total;
            }
        }

        log.warn("SLA evaluation stopped at the {}-batch cycle limit — backlog may still be draining",
                MAX_BATCHES_PER_CYCLE);
        return total;
    }
}
