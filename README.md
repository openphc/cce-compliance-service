# CCE Compliance Service

The time plane of the CCE system. Picks up the SLA transitions the Matcher Service scheduled as their
deadlines pass and records the resulting `OVERDUE` / `MISSED` deviations; separately sweeps
`step_instance` for completed steps that beat their `due_date` and records those as `MET`, needing no
schedule to do it. It writes `step_instance.sla_status` — it is the column's only writer — and
publishes the intelligence actions the deviations trigger.

It matches no events, enrols no patients and manages no definitions. It **owns no tables** and runs no
migrations. Kafka is **produce-only** — nothing inbound reaches this service.

> **Before you run it during an Event Replay: don't.** This service must be stopped while the Matcher
> Service still has an event backlog to process, or it will record `OVERDUE` and `MISSED` against steps
> whose completing event has not been matched yet — verdicts and clinician alerts that cannot be
> withdrawn. Why:
> [Architecture — Operational prerequisite](docs/architecture-overview.md#operational-prerequisite--event-replay).
> Runbook: [Deployment Guide](docs/deployment-guide.md#event-replay--sequencing-the-two-services).

**Port** `8092` · **Java** 21 · **Spring Boot** 3.4.2 · **Version** 2.0.0

## Quick Start

This service cannot create its own schema. Bring up the Protocol and Matcher services against `ccedb`
first — in that order — then:

```bash
./gradlew build
./gradlew bootRun

curl -s localhost:8092/actuator/health
```

Starting against an unmigrated database fails at boot on `ddl-auto: validate`, by design. Full
sequence: [Developer Setup](docs/developer-setup.md#quick-start).

## Documentation

| Document | Contents |
|---|---|
| [Architecture & Design](docs/architecture-overview.md) | The fetch-and-apply cycle, the applier's behaviour table, retry and backoff, observability, scaling |
| [API Reference](docs/api-reference.md) | The read-only intelligence-event API and the operational endpoints |
| [Developer Setup](docs/developer-setup.md) | Prerequisites, configuration and tuning, project layout, testing, and the invariants to preserve |
| [Deployment Guide](docs/deployment-guide.md) | Docker and Kubernetes, replica scaling, alerts, troubleshooting |

System-wide context lives in **cce-common-util** and is not restated here:

| For | See |
|---|---|
| Why the services are split, and the SLA handoff contract | `cce-common-util` → [docs/architecture-overview.md](../cce-common-util/docs/architecture-overview.md) |
| Schema, columns, enums, table ownership | `cce-common-util` → [docs/data-dictionary.md](../cce-common-util/docs/data-dictionary.md) |
| The shared entities, evaluator and exception handler this service uses | `cce-common-util` → [docs/library-reference.md](../cce-common-util/docs/library-reference.md) |
| Status vocabularies and their FHIR provenance | `cce-common-util` → [docs/fhir-conformance.md](../cce-common-util/docs/fhir-conformance.md) |

Cross-repository links assume the repositories are checked out as siblings, which is also what the
Gradle composite build assumes.

## How it works

```
@Scheduled poll ──> SlaTransitionEvaluator      drives; holds no transaction
                          │
        ┌─────────────────┴─────────────────┐
        ▼                                   ▼
  1. breach sweep                     2. on-time sweep
  step_sla_state_transition           step_instance
  is_processed = false,               COMPLETED, sla_status NULL,
  next_attempt_at <= now              completed_at < due_date
        │                                   │
        ▼                                   ▼
  SlaTransitionApplier                write MET
  one transaction per batch           (+ history row)
        │
        ├───────────────┬────────────────┐
        ▼               ▼                ▼
  step_instance      deviation      intelligence_event_log
  .sla_status                              │
                                           ▼
                                 Kafka cce.intelligence.triggers
```

Both sweeps run every cycle. A breach needs a schedule to come round; whether work was recorded on
time needs only the step's own `completed_at` and `due_date`, so it is settled without one — which is
what stops an early completion reading as null until its due date, weeks away.

The row lock **is** what reserves the row — no lease table, no heartbeat, no leader election. Every replica can
poll the same table concurrently, and a replica that dies mid-batch releases its rows immediately.

A row is fetched for one reason — its schedule came round — and what it decides is a breach:
`completed_at` against its `process_by`, never the wall clock. `MET` is not a row's to decide — the
on-time sweep settles that from the step's own `completed_at` and `due_date`, so an early completion is
recorded without waiting for a deadline that would only confirm it. Details in
[Architecture §3](docs/architecture-overview.md#3-the-fetch-and-apply-cycle).

## API

```
GET /v1/compliance/intelligence-events?protocolInstanceId=&actionDefinitionId=&published=
GET /v1/compliance/intelligence-events/{id}
```

Read-only. Everything this service writes is driven by its scheduler, never by a request.

## Testing

```bash
./gradlew test              # 67 tests (66 unit + a context-boot test)
./gradlew build             # tests + coverage gate (0.98 instruction coverage)
./gradlew jacocoTestReport  # build/reports/jacoco/test/html/index.html
```

Concurrent-fetch behaviour depends on real `FOR UPDATE SKIP LOCKED` semantics and must be verified
against PostgreSQL, not H2.
