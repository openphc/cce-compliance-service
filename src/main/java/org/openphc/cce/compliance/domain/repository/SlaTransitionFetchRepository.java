package org.openphc.cce.compliance.domain.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.openphc.cce.common.entity.StepSlaStateTransition;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The evaluator's fetch path over {@code step_sla_state_transition}.
 *
 * <p>Deliberately separate from cce-common-util's {@code StepSlaStateTransitionRepository}, which is the
 * shared read side (a step's thresholds, needed by both services). Fetching rows for processing is this
 * service's alone, so the query that does it — and the pessimistic lock it takes — lives here rather than
 * somewhere the Matcher Service could reach for it.
 *
 * <p>One fetch, one gate: {@code next_attempt_at} passing is the only thing that makes a row eligible.
 */
@Repository
public interface SlaTransitionFetchRepository extends JpaRepository<StepSlaStateTransition, UUID> {

    /**
     * Fetch a batch of transitions whose deadline has passed.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — expressed by the {@code -2} lock timeout — is what makes this
     * safe to run on every instance at once: each caller takes rows no one else holds and steps over the
     * rest instead of blocking. The row lock <em>is</em> what reserves the row, so no lease table and no leader
     * election are needed.
     *
     * <p>Selects on {@code next_attempt_at}, equal to {@code process_by} initially and pushed out by a
     * failure so a retry is deferred without rewriting {@code process_by} — which stays the immutable
     * record of when the deadline fell. Matches the partial index {@code idx_sslt_due}, so the scan
     * covers only the unprocessed backlog. Ordered by {@code process_by} so the oldest deadline is
     * always applied first, however often a row has been deferred.
     *
     * <p>The only way a transition row is fetched. There is deliberately no second path reaching rows by
     * their step's state — taking a settled step's remaining row ahead of its deadline would buy nothing
     * and cost the retry contract. Nothing would change: the verdict is a function of {@code process_by}
     * and the step's own columns, none of which move while the row is pending, so an early apply produces
     * exactly the outcome the deadline produces later. And such a query would have to ask for
     * {@code next_attempt_at > :now}, which is precisely the state {@code backOff} puts a failed row
     * into — it would re-fetch on the next cycle a row the back-off had just deferred, so the exponential
     * interval would never take effect for the rows it covered.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT t FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt <= :now
            ORDER BY t.processBy ASC
            """)
    List<StepSlaStateTransition> fetchDueTransitions(@Param("now") OffsetDateTime now, Limit limit);

    /**
     * The {@code cce.sla.transitions.due} gauge: rows the next cycle will fetch.
     *
     * <p>Carries {@link #fetchDueTransitions}'s predicate exactly, deliberately — the gauge has to count
     * what the next cycle will fetch, or it stops being a backlog. Counting every unprocessed row would
     * instead fold in the whole future schedule, so it would track enrolment volume rather than lateness
     * and could never sit near zero.
     */
    @Query("""
            SELECT COUNT(t) FROM StepSlaStateTransition t
            WHERE t.processed = false
              AND t.nextAttemptAt <= :now
            """)
    long countDueTransitions(@Param("now") OffsetDateTime now);
}
