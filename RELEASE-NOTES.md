# Release Notes — v1.0.0

**Release Date:** 2025  
**Component:** `cce-compliance-service`  
**Java:** 21 LTS | **Spring Boot:** 3.4.2 | **PostgreSQL:** 16 | **Kafka:** 3.x KRaft

---

## Overview

Initial release of the CCE Compliance Service — a clinical protocol compliance engine that tracks patient adherence to FHIR R4 `PlanDefinition` protocols. The service consumes clinical events via Kafka, matches them against loaded protocol definitions using a two-tier matching algorithm, enrolls patients, tracks step completion, and detects deviations.

---

## Feature Summary

### Protocol Management
- Load FHIR R4 `PlanDefinition` JSON resources as protocol definitions
- Parse and validate triggers, actions, timing, and conditions
- Build and maintain a `trigger_index` for fast structural matching
- Retire, rebuild-index, and delete protocol definitions
- Automatic registration of condition-only triggers in an in-memory cache

### Two-Tier Event Matching
- **Tier 1 — Structural matching:** GROUP BY + HAVING query against `trigger_index` enforces AND semantics across multiple codeFilter entries per action
- **Tier 2 — Condition evaluation:** JSONLogic (`text/jsonlogic`) and FHIRPath (`text/fhirpath`) expression evaluation against event payloads
- **Five exclusive matching scenarios:** (F1) resource type only, (F1,F2) type + codeFilters, (F1,F3) type + condition, (F1,F2,F3) type + codeFilters + condition, (F3) condition only
- **Explicit matching:** CloudEvents with `actionId` extension bypass Tier 1/2 entirely

### Patient Enrollment & Step Tracking
- Idempotent patient enrollment — existing active enrollments are reused
- Step state machine: `PENDING → DUE → OVERDUE → MISSED` (scheduler-driven) and `PENDING/DUE/OVERDUE → COMPLETED` (event-driven)
- Progressive step instantiation via `relatedAction` with offset-based due date calculation
- Recurring step support via `TimingInfo.count` with staggered due dates
- Auto-skip of optional (`could`) steps when subsequent steps complete
- Automatic protocol completion when all steps reach terminal states

### Deviation Detection
- Automatic deviation recording for OVERDUE and MISSED step transitions
- Deviation metadata includes timing details (days overdue, days past missed)
- Intelligence trigger events published to Kafka upon deviation detection

### Intelligence Rules & Actions
- **Intelligence rules** modeled as nested sub-actions within PlanDefinition steps
- Rule conditions evaluated via JSONLogic/FHIRPath against step runtime state (stepState, daysOverdue, completionStatus)
- Rules evaluated at two points: deviation detection (OVERDUE/MISSED) and step completion (late completion alerts)
- **Action Definitions** (`ActivityDefinition` resources) define what to do when a rule fires: `send-notification`, `create-task`, `forward-data`, `escalate`
- Action Definition CRUD via REST API (`POST/GET/PUT /v1/action-definitions`)
- **Action Runs** track execution of intelligence-triggered actions
- Action Run lifecycle: `pending → in_progress → completed/failed/cancelled`
- Action Run query and cancel via REST API (`GET/POST /v1/action-runs`)
- Intelligence summary endpoint (`GET /v1/intelligence/summary`) with counts by type, severity, and target
- `IntelligenceTriggerProducer` publishes events to `cce.intelligence.triggers` Kafka topic
- Intelligence event schema includes severity (`low/medium/high/critical`), target (`patient/assigned_worker/supervisor/facility`), and `definitionCanonical`
- CCE extensions on PlanDefinition: `intelligence-severity`, `intelligence-target`

### Event Processing
- CloudEvents v1.0 spec with CCE extension attributes (`correlationid`, `actionid`, `facilityid`, `protocolinstanceid`)
- Idempotency via `(cloudeventsId, source)` uniqueness on `event_log`
- Comprehensive event logging with processing status tracking

### Kafka Infrastructure
- **3 primary topics:** `cce.events.inbound`, `cce.scheduler.triggers`, `cce.intelligence.triggers`
- **2 DLQ topics:** `cce.events.inbound.dlq`, `cce.scheduler.triggers.dlq`
- 25 partitions per topic (configurable)
- `AckMode.RECORD` with `DefaultErrorHandler` + `FixedBackOff` retry + `DeadLetterPublishingRecoverer`
- `ErrorHandlingDeserializer` wrapping for poison pill protection

### REST API
- **26+ endpoints** across 5 controllers:
  - Protocol Definitions (8): load, list, get by ID, get by URL, get by URL+version, retire, rebuild-index, delete
  - Protocol Instances (2): get by ID, withdraw
  - Patient Tracking (6): list instances, active instances, instance detail, steps, deviations, events
  - Action Definitions (4): register, list, get by ID, update
  - Action Runs (4): list, get by ID, get status, cancel
  - Intelligence (1): summary
- Structured error responses via `GlobalExceptionHandler`
- DTOs mapped via `DtoMapper` — entities never exposed in responses

### Observability
- **Micrometer metrics:** `cce.events.processed`, `cce.events.matched`, `cce.events.duplicate`, `cce.events.zero_match`, `cce.step.matching.duration`, `cce.events.processing.duration`, `cce.protocol.instances.active` (gauge)
- Prometheus endpoint at `/actuator/prometheus`
- Health probes enabled for Kubernetes liveness/readiness
- Structured logging with SLF4J/Logback

### Infrastructure
- Multi-stage Docker build with Eclipse Temurin 21 JRE Alpine
- Non-root container execution
- Docker Compose for local development (PostgreSQL 16 + Kafka KRaft)
- Flyway schema migrations (`ddl-auto=validate`)
- HikariCP connection pool tuning
- Production profile (`application-prod.yml`) with optimized settings

---

## Architecture

```
Kafka ─→ InboundEventConsumer ─→ ComplianceEngine
                                    ├── Idempotency (EventLogService)
                                    ├── Resource Extraction (ResourceInfoExtractor)
                                    ├── Tier 1 Matching (TriggerMatchingService)
                                    ├── Tier 2 Evaluation (ExpressionEvaluationService)
                                    ├── Enrollment (ProtocolInstanceService)
                                    ├── Step Management (StepInstanceService)
                                    ├── Deviation Detection (DeviationService)
                                    ├── Intelligence Rules (IntelligenceRuleService)
                                    ├── Action Definitions (ActionDefinitionService)
                                    └── Audit Logging (AuditService)
```

---

## Database Schema

9 tables managed via Flyway:
- `protocol_definition` — FHIR PlanDefinition storage with JSONB definition column
- `protocol_instance` — Patient enrollment tracking
- `step_instance` — Individual step state tracking
- `deviation` — Deviation records with metadata
- `trigger_index` — Decomposed codeFilter entries for Tier 1 matching (composite PK)
- `event_log` — Inbound event log with idempotency constraint
- `audit_log` — Audit trail for all operations
- `action_definition` — Intelligence action definitions (ActivityDefinition resources)
- `action_run` — Intelligence action execution records

---

## Known Limitations (v1.0.0)

- **No CQL support:** Only JSONLogic (`text/jsonlogic`) and FHIRPath (`text/fhirpath`) expression languages are supported. CQL evaluation was removed from scope.
- **`cce.protocol.control` topic reserved:** Reserved for future use — not implemented in 1.0.0.
- **No multi-tenancy:** Single-tenant deployment assumed.
- **No authentication at service level:** Security is handled by the CCE API Gateway.
- **Intelligence event delivery is external:** The Compliance Service publishes intelligence trigger events to Kafka. Actual delivery to Receiver Adaptors (webhooks, topic subscriptions) is handled by the CCE Intelligence Service.

---

## Breaking Changes

N/A — initial release.

---

## Upgrade Instructions

N/A — initial release.

---

## Test Coverage

- **258 unit tests** covering all services, controllers, and mappers
- **24 integration tests** covering end-to-end workflows with EmbeddedKafka + H2
- JaCoCo coverage reports via `./gradlew test jacocoTestReport`
