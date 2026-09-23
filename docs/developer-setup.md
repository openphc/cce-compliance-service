# Developer Setup — Compliance Service

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 | Gradle toolchain |
| PostgreSQL 16 | shared `ccedb` — **must already contain the schema** (see below) |
| Kafka | producer only |
| `cce-common-util` | checked out as a sibling directory — wired in as a composite build |

This service **owns no tables and runs no migrations**, so it cannot bring up its own schema. Start
the Protocol Service and then the Matcher Service against an empty `ccedb` first; both apply their
migrations on startup. Starting this service against a schema-less database fails at boot on
`ddl-auto: validate`, which is the intended behaviour.

## Quick start

```bash
# 1. Shared infrastructure
cd ../cce-collector-service && docker compose up -d postgres kafka

# 2. Schema — from the two services that own it, in this order
cd ../cce-protocol-service && ./gradlew bootRun   # creates 4 tables
cd ../cce-matcher-service  && ./gradlew bootRun   # creates 9 tables

# 3. This service
./gradlew build && ./gradlew bootRun

# 4. Verify
curl -s localhost:8092/actuator/health
```

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `8092` | |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | `5433` for the collector's shared instance |
| `DB_NAME` | `ccedb` | |
| `DB_USERNAME` / `DB_PASSWORD` | `cce_user` / `cce_pass` | needs **no** DDL rights |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `CCE_SLA_POLL_INTERVAL_MS` | `5000` | how often to look for due transitions |
| `CCE_SLA_BATCH_SIZE` | `100` | rows fetched per transaction |
| `CCE_SLA_INSTANCE_ID` | `$HOSTNAME` | recorded in `processed_by` |
| `CCE_SLA_MAX_BACKOFF_SECONDS` | `3600` | cap on the `2^attempts` retry backoff |
| `CCE_PARSED_PROTOCOL_CACHE_SIZE` | `256` | shared parsed-protocol cache |
| `CCE_PUBLISH_CONFIRM_TIMEOUT_MS` | `5000` | how long to wait for a broker ack before recording the trigger unpublished |

### Tuning the sweep

`poll-interval-ms` is the steady-state cost, not the drain rate — a backlog is cleared within a single
cycle because batches are drained until one comes back short
([Architecture §3](architecture-overview.md#3-the-fetch-and-apply-cycle)). Lowering it shortens the *detection*
delay for a newly-due transition; it does not make a backlog clear faster.

`batch-size` trades transaction length against round trips. Larger batches hold row locks longer, which
only matters when the Matcher Service is inserting into `step_sla_state_transition` heavily at the same
time.

## Project layout

```
org.openphc.cce.compliance
├── service/SlaTransitionEvaluator   @Scheduled driver — polls, loops, holds no transaction
├── service/SlaTransitionApplier     the @Transactional boundary — fetch and apply
├── service/IntelligenceEventLogService
├── domain/repository/SlaTransitionFetchRepository   the SKIP LOCKED fetch of due rows
├── domain/repository/OnTimeStepFetchRepository      the SKIP LOCKED sweep for MET
├── web/controller/IntelligenceEventLogController
├── web/DtoMapper, web/dto/
└── config/  KafkaConfig (produce-only), ObservabilityConfig
```

Entities, repositories, `DeviationRecorder` and `IntelligenceActionEvaluator` come from
`cce-common-util`. What this service adds is the fetch queries, the transaction boundary and the
scheduler.

`build.gradle` reflects that: it declares no FHIR, JSONLogic or Flyway dependency of its own. The FHIR
layer arrives transitively through `cce-common-util`, and Flyway would be dead weight in a service that
owns no tables. Redeclaring any of them here would only be a second version to keep in step.

The driver/applier split is not stylistic — see
[Architecture §3](architecture-overview.md#why-a-driver-and-an-applier). If you merge them, the
`@Transactional` annotation silently stops taking effect.

## Testing

```bash
./gradlew test              # 54 tests — 53 unit plus one context-boot test
./gradlew build             # tests + coverage gate
./gradlew jacocoTestReport
```

The coverage gate is **0.98** instruction coverage, excluding `ComplianceServiceApplication`.

`ApplicationContextTest` boots the real context on H2 with Flyway disabled and the poll interval widened so the sweep does not repeat. It is
the only test that exercises the wiring: everything else constructs its subject directly, which leaves a
bean this service needs at runtime but never names in source invisible behind the coverage figure. It
also validates the fetch queries — Spring Data parses every `@Query` at bootstrap, so a typo in the JPQL
fails there rather than on the first poll in production. That matters more here than in the sibling
services, because this one runs `ddl-auto: validate` against a schema it does not own: a mapping it gets
wrong is a failure to start.

There is no integration-test source set. The behaviour that would justify one — concurrent fetches
across replicas — cannot be reproduced against H2, because `FOR UPDATE SKIP LOCKED` semantics are the
thing under test. Verify that against real PostgreSQL.

Controller tests build MockMvc with `MockMvcBuilders.standaloneSetup` rather than `@WebMvcTest`,
because the application class carries `@EnableJpaRepositories` and a web slice would fail looking for
an `entityManagerFactory`. They register a `PageableHandlerMethodArgumentResolver` explicitly, since
standalone setup does not supply one.

## Working on the applier

Four invariants to preserve:

1. **Never write `step_status`.** It belongs to the Matcher Service. Column ownership is what keeps the
   two services from overwriting each other —
   [Architecture Overview §4](../../cce-common-util/docs/architecture-overview.md#4-step-status-and-sla-status).
2. **Judge against `completed_at`, never the wall clock.** The row was fetched because its deadline
   passed; the only remaining question is whether the work had happened by then, and the clinical
   occurrence time is the evidence for that.
3. **Write `MET` only on the `DUE_DATE_REACHED` row, and only over a null.** Beating the missed date
   means the step was not written off, not that it was on time — a step completed between its two
   thresholds is `OVERDUE`, and `writeSlaStatus`'s forward-only rule is what keeps a retry applying
   rows out of order from walking that back.
4. **Keep the `MISSED` status and deviation `must`-only on every path.** `isOptionalMiss` is
   deliberately shared by the completed and outstanding paths. Applying the exemption to only one would
   make an optional step recorded late worse off than one never recorded at all.

All four are asserted by the existing tests; a change that breaks any of them will fail rather than
silently corrupt a step.

When adding a case to the behaviour table, add it to
[Architecture §4](architecture-overview.md#4-what-the-applier-does) as well — that table is the spec,
and a case that exists in code but not there is undiscoverable.
