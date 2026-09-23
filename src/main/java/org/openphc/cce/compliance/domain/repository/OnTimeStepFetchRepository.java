package org.openphc.cce.compliance.domain.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.openphc.cce.common.entity.StepInstance;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Steps that beat their due date and are still waiting to be told so.
 *
 * <p>Reads {@code step_instance} directly, which is the point: whether work was recorded on time is a
 * question about the step, answerable from the step's own two columns — {@code completed_at} against
 * {@code due_date} — with no schedule to consult. {@code MET} is therefore settled here rather than by
 * fetching the step's {@code DUE_DATE_REACHED} row, which exists to detect a <em>breach</em>.
 *
 * <p>The practical gain is promptness. A schedule can only fire at a deadline, so a step recorded early
 * would read as a null {@code sla_status} until its due date arrived — weeks, for a step done well
 * ahead. The answer is fixed the moment the completion lands, so this sweep records it on its next pass.
 *
 * <p>Separate from cce-common-util's {@code StepInstanceRepository}, which is the shared read side, for
 * the same reason {@code SlaTransitionFetchRepository} is: fetching rows to act on is this service's
 * alone, and the pessimistic lock it takes should not be somewhere another service could reach for it.
 */
@Repository
public interface OnTimeStepFetchRepository extends JpaRepository<StepInstance, UUID> {

    /**
     * Fetch a batch of completed steps whose work beat the step's due date, and whose timeliness has
     * not been recorded yet.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — the {@code -2} lock timeout — as in the transition fetch: a
     * row held by one replica is invisible to the others rather than contended, so every replica can
     * sweep the same table at once and a replica that dies mid-batch releases its rows at once.
     *
     * <p>{@code sla_status IS NULL} is both the filter and the idempotency guard. There is no
     * {@code processed} flag to set here, because the status itself is the record that the question has
     * been answered: a step written {@code MET} leaves this set and cannot be fetched again. A step
     * already {@code OVERDUE} is excluded for the same reason — that verdict is settled, and
     * {@code MET} must never overwrite it.
     *
     * <p>Matches the leading columns of {@code idx_step_instance_completed_unjudged}, the partial index
     * over completed-but-unsettled steps, so the scan covers that transient set rather than every step
     * ever created. {@code due_date IS NOT NULL} excludes a step created from its own trigger, which has
     * no deadline to have beaten. Ordered by {@code completed_at} so the longest-waiting is recorded
     * first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT s FROM StepInstance s
            WHERE s.stepStatus = org.openphc.cce.common.enums.StepStatus.COMPLETED
              AND s.slaStatus IS NULL
              AND s.completedAt IS NOT NULL
              AND s.dueDate IS NOT NULL
              AND s.completedAt < s.dueDate
            ORDER BY s.completedAt ASC
            """)
    List<StepInstance> fetchOnTimeSteps(Limit limit);

    /**
     * How many steps are waiting to be recorded as {@code MET}.
     *
     * <p>Carries {@link #fetchOnTimeSteps}'s predicate so the gauge counts what the next sweep will
     * take. Sits near zero in a healthy system, since a sweep empties the set.
     */
    @Query("""
            SELECT COUNT(s) FROM StepInstance s
            WHERE s.stepStatus = org.openphc.cce.common.enums.StepStatus.COMPLETED
              AND s.slaStatus IS NULL
              AND s.completedAt IS NOT NULL
              AND s.dueDate IS NOT NULL
              AND s.completedAt < s.dueDate
            """)
    long countOnTimeSteps();
}
