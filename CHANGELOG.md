# Changelog

All notable changes to the CCE Compliance Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-05

### Added

#### Sub-Step Groups
- `PlanDefinition.action.action[]` with `type.coding[0].code = "sub-step"` creates child step instances within a parent group
- `SubStepActionInfo` record: id, title, triggers, relatedActions, timing, toleranceDays, requiredBehavior, intelligenceActions
- `PlanDefinitionParser.classifyNestedActions()` — routes nested actions to either sub-step or intelligence action builders
- Sub-step trigger indexing with composite actionId format (`parentActionId/subStepId`) in `TriggerIndex`
- `StepInstanceService.createSubSteps()` — creates entry-point sub-steps for a newly instantiated group
- `StepInstanceService.createDependentSubSteps()` — progressive sibling instantiation via `relatedAction` scoped to group
- `StepInstanceService.evaluateGroupCompletion()` — auto-completes parent when `selectionBehavior` is satisfied
- `ComplianceEngine.processSubStepMatch()` — handles composite actionId routing through the engine
- `ComplianceEngine.evaluateSubStepConditions()` — Tier 2 condition evaluation for sub-step triggers
- Group completion via FHIR `selectionBehavior`: all, any, exactly-one, at-most-one, one-or-more, all-or-none
- Duplicate creation guard in progressive sub-step instantiation
- Validation: rejects PlanDefinition actions with both triggers AND sub-steps (mutually exclusive)
- Flyway V5 migration: `parent_step_id` (UUID FK → step_instance), `parent_action_id` (VARCHAR) + index on `step_instance`

#### Schema Optimization
- Flyway V4 migration: Performance indexes and constraints for production workloads

### Changed

#### Parser & Metadata
- `ActionMetadata` record extended with `groupingBehavior`, `selectionBehavior`, `subSteps` fields and `hasSubSteps()` method
- `buildTriggerIndexEntries()` recurses into sub-step triggers for composite actionId construction
- `validateTriggers()` enforces mutual exclusivity between action triggers and sub-steps

#### Engine Flow
- `ComplianceEngine.processMatch()` — detects composite actionId (contains "/") and routes to sub-step processing
- `performTwoTierMatching()` — handles composite actionId for sub-step trigger condition evaluation
- `StepInstanceService.completeStep()` — when `parentStepId != null`, triggers progressive sibling creation + group completion evaluation

#### Bug Fixes
- `isGroupComplete()` — `all-or-none` no longer returns true when 0 completions exist (requires all complete)
- Dead code removal: unused `hasDependency` variable in `createDependentSubSteps()`
- Performance: PlanDefinition parsed once in sub-step completion flow (was parsed twice)
- Misleading V5 migration comment fixed (referenced columns never added)

---

## [1.1.0] - 2026

### Added

#### Intelligence Pipeline
- `IntelligenceActionEvaluator` — core engine evaluating PlanDefinition intelligence actions on deviation detection and step completion
- Intelligence action extraction from nested `PlanDefinition.action.action[]` with condition (JSONLogic/FHIRPath), `definitionCanonical`, severity, and intelligence destination extensions
- `IntelligenceTriggerProducer` — publishes `IntelligenceTriggerEvent` to `cce.intelligence.triggers` Kafka topic (fire-and-forget, keyed by protocolInstanceId)
- `IntelligenceEventLog` entity recording each intelligence action execution with evaluation context and Kafka event payload
- `ActionDefinition` entity for FHIR `ActivityDefinition` resources — CRUD operations via `ActionDefinitionService`
- Flyway V2 migration: `action_definition`, `intelligence_event_log` tables with indexes
- Flyway V3 migration: `ORDER_VIOLATION` added to `deviation_type` CHECK constraint
- `ORDER_VIOLATION` deviation type for detecting out-of-sequence step completions

#### New Enums
- `ActionDefinitionStatus` (ACTIVE, RETIRED)
- `ActionType` (CommunicationRequest, Task, ServiceRequest)
- `IntelligenceSeverity` (LOW, MEDIUM, HIGH, CRITICAL)

#### REST API
- `ActionDefinitionController` — 6 endpoints: POST create, GET list (filter by status), GET by ID, PUT update, POST retire, DELETE
- `IntelligenceEventLogController` — 2 endpoints: GET list (filter by protocolInstanceId, actionDefinitionId, published), GET by ID
- DTOs: `ActionDefinitionDto`, `IntelligenceEventLogDto`

#### Observability
- `cce.intelligence.actions.evaluated` counter — total intelligence action conditions evaluated
- `cce.intelligence.actions.fired` counter — actions that matched and triggered
- `cce.intelligence.publish.duration` timer — Kafka publish latency
- `cce.action.definitions.active` gauge — active action definitions count
- `cce.events.intelligence.published` counter — now actively incremented
- MDC `intelligenceEventId` context during intelligence event publishing

#### Wiring
- `StepInstanceService.applySchedulerTransition()` — calls `evaluateOnDeviation()` after OVERDUE/MISSED deviation creation
- `ComplianceEngine.processMatch()` / `processExplicitMatch()` — calls `evaluateOnCompletion()` after step completion

### Changed

#### Performance Optimizations
- `IntelligenceActionEvaluator` — bounded `ConcurrentHashMap` cache for parsed PlanDefinition objects, avoiding FHIR re-parsing on every deviation/completion evaluation
- `PlanDefinitionParser.ActionMetadata` record — extended with `List<IntelligenceActionInfo> intelligenceActions` field

### Testing
- 351 unit tests (was 254 in v1.0.0) — 97 new tests for intelligence pipeline
- 39 integration tests (was 24 in v1.0.0) — 15 new: `ActionDefinitionApiIntegrationTest` (9), `IntelligencePipelineIntegrationTest` (6)

---

## [1.0.0] - 2025

### Added

#### Core Engine
- `ComplianceEngine` central orchestrator for inbound clinical event processing
- Two-tier matching pipeline: Tier 1 structural (GROUP BY + HAVING) + Tier 2 condition evaluation (JSONLogic/FHIRPath)
- Five exclusive matching scenarios: (F1), (F1,F2), (F1,F3), (F1,F2,F3), (F3)
- Explicit match processing via CloudEvent `actionId` extension
- Per-event action metadata cache to eliminate redundant PlanDefinition parsing in hot path
- JSONLogic rule cache for compiled expression reuse across events

#### Protocol Management
- FHIR R4 `PlanDefinition` loading, parsing, validation, and storage
- Trigger index construction from codeFilter decomposition
- In-memory condition-only trigger cache (ConcurrentHashMap)
- Protocol retire, rebuild-index, and delete operations

#### Patient Tracking
- Idempotent patient enrollment to protocol instances
- Step state machine: PENDING → DUE → OVERDUE → MISSED (scheduler) / COMPLETED (event)
- Progressive step instantiation via `relatedAction` offsets
- Recurring step support with `TimingInfo.count`
- Auto-skip of optional (`could`) steps
- Automatic protocol completion detection

#### Deviation Detection
- Automatic deviation recording for OVERDUE and MISSED transitions
- Deviation metadata with timing details

#### Kafka Integration
- `InboundEventConsumer` for clinical events (CloudEvents v1.0)
- `SchedulerTriggerConsumer` for scheduler-driven state transitions
- 5 topic declarations (3 primary + 2 DLQ) with 25 partitions each
- `DefaultErrorHandler` with `FixedBackOff` retry and `DeadLetterPublishingRecoverer`
- `ErrorHandlingDeserializer` wrapping for poison pill protection
- `AckMode.RECORD` for per-record offset commits

#### REST API
- 16 endpoints across 3 controllers (Protocol Definitions, Protocol Instances, Patient Tracking)
- DTOs with entity-to-DTO mapping via `DtoMapper`
- `GlobalExceptionHandler` with structured error responses

#### Domain Model
- 7 JPA entities with UUID primary keys (except `TriggerIndex` composite PK)
- 7 enums for type-safe status values
- JSONB column support via Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`
- Flyway schema migrations with `ddl-auto=validate`
- Fetch-join query for protocol instance details (steps + deviations)

#### Observability
- Micrometer counters: `cce.events.processed`, `cce.events.matched`, `cce.events.duplicate`, `cce.events.zero_match`
- Micrometer timers: `cce.step.matching.duration`, `cce.events.processing.duration`
- Micrometer gauge: `cce.protocol.instances.active`
- Prometheus endpoint, health probes, structured logging

#### Infrastructure
- Spring Boot 3.4.2 / Java 21 with Gradle build
- Multi-stage Dockerfile (Temurin 21 JRE Alpine, non-root)
- Docker Compose (PostgreSQL 16 + Kafka KRaft)
- Production profile (`application-prod.yml`)
- HikariCP connection pool tuning
- JaCoCo code coverage reporting

#### Testing
- 254 unit tests (MockMvc, mocked services)
- 24 integration tests (EmbeddedKafka + H2)
- Integration test source set with separate configuration

#### Documentation
- Architecture & design overview
- API reference with all endpoints
- Data dictionary with ER diagram
- Kafka event architecture
- Developer setup guide
- Flow diagrams (Mermaid)
- Deployment guide
- Release notes

### Removed
- CQL expression evaluation (out of scope for v1.0.0)
- Intelligence trigger Kafka publishing (deferred to future phase)
- `cce.protocol.control` topic (reserved for future use)
