package org.openphc.cce.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the real application context.
 *
 * <p>Every other test here constructs its subject directly or builds a standalone MockMvc, so nothing
 * else exercises the actual wiring. That leaves a class of failure invisible behind a high coverage
 * figure: a bean this service never names in source but needs at runtime. {@code scanBasePackages},
 * {@code @EntityScan} and {@code @EnableJpaRepositories} are all widened to {@code org.openphc.cce}, so
 * the context also instantiates what cce-common-util contributes — including
 * {@code FhirExpressionEvaluator}, whose constructor needs JSONLogic on the classpath even though
 * this service declares no JSONLogic dependency of its own.
 *
 * <p>It also validates {@code SlaTransitionFetchRepository}: Spring Data parses every {@code @Query} at
 * bootstrap, so a typo in the fetch JPQL fails here rather than on the first poll in production. That
 * matters more for this service than for its siblings, because it runs {@code ddl-auto: validate}
 * against a schema two other services own — a mapping it gets wrong is a failure to start, not a
 * failure to serve.
 *
 * <p>H2 with Flyway disabled and the poller parked: the subject is bean wiring, not the schema (the
 * owning migrations cover that) and not {@code FOR UPDATE SKIP LOCKED}, whose semantics only PostgreSQL
 * reproduces.
 */
@SpringBootTest
@ActiveProfiles("contexttest")
class ApplicationContextTest {

    @Test
    void contextLoads() {
        // Fails on any wiring, mapping or missing-runtime-dependency problem.
    }
}
