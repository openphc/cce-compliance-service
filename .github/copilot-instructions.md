# CCE Compliance Service — AI Agent Instructions

## What this service is

The **time plane** of the CCE system. Spring Boot 3.4.2 / Java 21. Claims the
`step_sla_state_transition` rows the Matcher Service scheduled once their deadlines pass, advances
`step_instance.sla_status`, records the resulting `OVERDUE` / `MISSED` deviations, and publishes the
intelligence actions they trigger.

It is small on purpose — 10 source files. Almost everything it needs comes from `cce-common-util`;
what it adds is a claim query, a transaction boundary and a scheduler.

**Owns no tables. Runs no migrations.** Flyway is disabled; `ddl-auto: validate`.
**Kafka is produce-only** — there is no consumer, no listener container, no DLQ.

Three sibling repositories share the work. Do not implement their concerns here:

| Repository | Owns |
|---|---|
| `cce-protocol-service` | Loading/retiring definitions, building `trigger_index` |
| `cce-matcher-service` | Event matching, enrolment, step creation/completion, `ORDER_VIOLATION` |
| `cce-common-util` | Shared entities, repositories, `DeviationRecorder`, `IntelligenceActionEvaluator`, exception handler |

`cce-common-util` is a Gradle **composite build** (`includeBuild '../cce-common-util'`), so it must be
checked out as a sibling directory.

## Read these first

Documentation is not duplicated between repositories. Start here:

- `docs/architecture-overview.md` — the fetch-and-apply cycle, the applier's behaviour table, retry, observability
- `docs/developer-setup.md` — configuration, tuning, and the invariants to preserve
- `docs/api-reference.md` — the read-only intelligence-event API
- `../cce-common-util/docs/architecture-overview.md#5-sla-transition-contract` — the contract this service implements
- `../cce-common-util/docs/data-dictionary.md#3-ownership` — which service writes which column

## Conventions

- **Package** `org.openphc.cce.compliance` — 10 source files
- **No local entities or enums.** All of them live in `cce-common-util`; never redeclare one here
- **Timestamps**: `OffsetDateTime` in UTC (`hibernate.jdbc.time_zone=UTC`)
- **DTOs** in `web/dto/`, mapped by `DtoMapper` — never expose an entity in a response
- The application widens component scanning to `org.openphc.cce` and adds `@EntityScan` /
  `@EnableJpaRepositories` for the same package, because `@Entity` types and repository interfaces are
  not component-scanned

## Invariants — breaking these corrupts data

1. **Never write `step_status`.** It belongs to the Matcher Service. Splitting the two columns was the
   whole point; see `../cce-common-util/docs/data-dictionary.md#3-ownership`.
2. **Never overwrite `sla_status` on an already-completed step.** The Matcher Service settled it from
   the clinical occurrence time, which is better evidence than the clock. Record the deviation instead.
3. **Claim and apply must share one transaction.** Splitting them creates a window where a row is
   marked taken but not acted on, and a crash there makes that permanent.
4. **Keep `SlaTransitionEvaluator` and `SlaTransitionApplier` as separate beans.** `@Transactional`
   takes effect only through the Spring proxy — a scheduled method calling a transactional method on
   *itself* runs with no transaction at all. Merging them silently disables the annotation.
5. **Gate intelligence evaluation on the deviation being new.** Otherwise a retried transition
   re-sends an alert a clinician already received.
6. **`backOff` must be `REQUIRES_NEW`.** It runs after a rollback; joining that transaction would roll
   the backoff back too and spin the row in a tight retry loop.

## How the sweep works

```
@Scheduled poll → SlaTransitionEvaluator  (drives, no transaction)
                → SlaTransitionApplier    (@Transactional, one per batch)
                → claimDue(now, batchSize) FOR UPDATE SKIP LOCKED, ORDER BY process_by ASC
```

The row lock **is** the claim — no lease table, no heartbeat, no leader election. Every replica may
poll concurrently. Batches are drained until one comes back short, so a backlog clears in one cycle
rather than one batch per interval (`MAX_BATCHES_PER_CYCLE` = 100 caps it). `poll()` never propagates,
or a failed cycle would kill the scheduler thread.

Applier behaviour depends on the step as found, because the event may have arrived between scheduling
and the deadline — the table is in `docs/architecture-overview.md#4-what-the-applier-does`. Add a case
there whenever you add one in code.

An optional (`could`) step that misses resolves to `MET` with no deviation.

## Build & test

```bash
./gradlew build                     # tests + coverage gate
./gradlew test                      # 44 unit tests
./gradlew jacocoTestReport
```

Coverage gate: **0.98** instruction coverage, excluding `ComplianceServiceApplication`.

There is no integration-test source set. The behaviour worth integration-testing — concurrent claims
across replicas — depends on real `FOR UPDATE SKIP LOCKED` semantics and cannot be reproduced on H2.
Verify it against PostgreSQL.

Controller tests use `MockMvcBuilders.standaloneSetup`, not `@WebMvcTest`: the application class
carries `@EnableJpaRepositories`, so a web slice fails looking for an `entityManagerFactory`.

Local run requires the schema to exist first — this service cannot create it:

```bash
cd ../cce-collector-service && docker compose up -d      # PostgreSQL + Kafka
cd ../cce-protocol-service  && ./gradlew bootRun         # creates 4 tables
cd ../cce-matcher-service   && ./gradlew bootRun         # creates 9 tables
cd ../cce-compliance-service && ./gradlew bootRun
curl localhost:8092/actuator/health
```

Docker images build from the **workspace** directory, not the repository, because of the composite
build: `docker build -f cce-compliance-service/Dockerfile -t cce-compliance-service:2.0.0 .`

## Not in scope

- Matching events, enrolling patients, creating or completing steps — `cce-matcher-service`
- `ORDER_VIOLATION` deviations — detected from the event at completion, so they stay in the Matcher Service
- Loading or retiring definitions — `cce-protocol-service`
- Intelligence delivery and routing — the CCE Intelligence Service consumes `cce.intelligence.triggers`
