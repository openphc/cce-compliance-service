# Changelog

All notable changes to the CCE Compliance Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

#### Intelligence Rules & Actions
- 9 additional endpoints across 3 controllers (Action Definitions, Action Runs, Intelligence Summary)
- `IntelligenceRuleService` evaluates nested PlanDefinition sub-actions on deviation and step completion
- `IntelligenceTriggerProducer` publishes to `cce.intelligence.triggers` with failure metrics
- `ActionDefinitionService` and `ActionRunService` for intelligence action lifecycle

#### Domain Model
- 9 JPA entities with UUID primary keys (except `TriggerIndex` composite PK)
- 11 enums for type-safe status values
- JSONB column support via Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`
- Flyway schema migrations with `ddl-auto=validate`
- Fetch-join query for protocol instance details (steps + deviations)

#### Observability
- Micrometer counters: `cce.events.processed`, `cce.events.matched`, `cce.events.duplicate`, `cce.events.zero_match`, `cce.events.intelligence.published`, `cce.events.intelligence.failed`, `cce.intelligence.rules.fired`, `cce.intelligence.rule.evaluation.errors`
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
- 258 unit tests (MockMvc, mocked services)
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
- `cce.protocol.control` topic (reserved for future use)
