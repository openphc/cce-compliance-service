package org.openphc.cce.compliance.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.openphc.cce.compliance.domain.repository.OnTimeStepFetchRepository;
import org.openphc.cce.compliance.domain.repository.SlaTransitionFetchRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Metrics for the compliance plane.
 *
 * <p>The counters this service moves are registered where they are incremented
 * ({@code cce.sla.transitions.*}, {@code cce.sla.evaluator.*}, {@code cce.intelligence.actions.*}). What
 * belongs here is the one thing only a gauge can express: how much due work is still outstanding.
 *
 * <p>{@code cce.sla.steps.on-time-unsettled} is the second: steps that beat their due date and are
 * waiting for the sweep that records it. It answers a different question from the transition gauge — one
 * counts breaches waiting to be detected, the other on-time work waiting to be acknowledged — and a step
 * sitting in it is not late, merely unreported.
 *
 * <p>{@code cce.sla.transitions.due} is the service's primary health signal. In a steady state it hovers
 * near zero; a rising value means transitions are falling due faster than they are being applied, or that
 * rows are failing and backing off. It counts what the next cycle would fetch — rows whose deadline has
 * passed, plus rows of steps already completed and so already judgeable — and nothing else: counting every
 * unprocessed row would fold in the whole future schedule and track enrolment volume instead.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterBinder complianceMetrics(SlaTransitionFetchRepository transitionRepository,
                                         OnTimeStepFetchRepository onTimeStepRepository) {
        return registry -> {
            Gauge.builder("cce.sla.transitions.due",
                            transitionRepository,
                            ObservabilityConfig::readyNow)
                    .description("SLA transition rows the next cycle would fetch: unprocessed, with "
                            + "next_attempt_at already passed")
                    .register(registry);

            Gauge.builder("cce.sla.steps.on-time-unsettled",
                            onTimeStepRepository,
                            OnTimeStepFetchRepository::countOnTimeSteps)
                    .description("Completed steps that beat their due date and have not been recorded "
                            + "as MET yet")
                    .register(registry);
        };
    }

    /**
     * What the next cycle would fetch, carrying {@code fetchDueTransitions}'s predicate exactly. A gauge
     * over every unprocessed row would fold in the whole future schedule, so it would track enrolment
     * volume rather than lateness and could never sit near zero.
     */
    private static double readyNow(SlaTransitionFetchRepository repository) {
        return repository.countDueTransitions(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
