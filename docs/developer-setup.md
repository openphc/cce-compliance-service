# Developer Setup & Configuration

## 1. Prerequisites

| Tool | Version | Required | Purpose |
|---|---|---|---|
| **Java JDK** | 21 LTS | Yes | Build and runtime |
| **Gradle** | 8.x | Yes | Build tool (via wrapper) |
| **Docker** | 24+ | Recommended | Run PostgreSQL, Kafka locally |
| **Docker Compose** | 2.x | Recommended | Orchestrate infrastructure |
| **PostgreSQL** | 16+ | Yes | Primary database |
| **Apache Kafka** | 3.x | Yes | Message broker |
| **Git** | 2.x | Yes | Version control |

## 2. Quick Start

### 2.1 Clone & Build

```bash
# Clone the repository
git clone <repository-url>
cd cce-compliance-service

# Build (skip tests for fast iteration)
./gradlew build -x test

# Build with tests
./gradlew build
```

### 2.2 Start Infrastructure

PostgreSQL, Kafka, and the shared database (`ccedb`) are deployed by the **CCE Collector Service**. All CCE services share the same database.

```bash
# Start shared infrastructure (PostgreSQL on port 5433 + Kafka on port 9092)
cd /path/to/cce-collector-service
docker compose up -d

# Verify shared services are running
docker compose ps
```

### 2.3 Run the Application

```bash
# Using Gradle
./gradlew bootRun

# Or using the JAR
java -jar build/libs/cce-compliance-service-1.0.0.jar

# With custom configuration
DB_HOST=localhost DB_PORT=5433 java -jar build/libs/cce-compliance-service-1.0.0.jar
```

### 2.4 Verify Health

```bash
# Health check (default local port is 8091 unless SERVER_PORT is overridden)
curl http://localhost:8091/actuator/health

# Expected response
# {"status":"UP","components":{"db":{"status":"UP"},"kafka":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

## 3. Configuration Reference

### 3.1 Environment Variables

All configuration can be overridden via environment variables:

#### Database

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port. The collector service exposes Postgres on `5433`, so set `DB_PORT=5433` for local dev against shared infrastructure |
| `DB_NAME` | `ccedb` | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `20` | HikariCP max pool size |
| `DB_POOL_MIN_IDLE` | `5` | HikariCP minimum idle connections |
| `DB_CONNECTION_TIMEOUT` | `30000` | HikariCP connection timeout (ms) |
| `DB_IDLE_TIMEOUT` | `600000` | HikariCP idle timeout (ms) |
| `DB_MAX_LIFETIME` | `1800000` | HikariCP max connection lifetime (ms) |

#### Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |

#### Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8091` | Application port |

### 3.2 Kafka Topic Configuration

Configured via `cce.kafka.topics.*` in `application.yml`:

| Property | Default Value | Description |
|---|---|---|
| `cce.kafka.topics.inbound-events` | `cce.events.inbound` | Inbound clinical events |
| `cce.kafka.topics.scheduler-triggers` | `cce.scheduler.triggers` | Scheduler timer triggers |
| `cce.kafka.topics.intelligence-triggers` | `cce.intelligence.triggers` | Outbound intelligence trigger events |

### 3.3 JPA & Hibernate

| Property | Value | Description |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `none` | Schema managed entirely by Flyway; Hibernate performs no DDL or validation |
| `spring.jpa.open-in-view` | `false` | Prevents lazy loading in controllers (best practice) |
| `hibernate.dialect` | `PostgreSQLDialect` | Not set explicitly in `application.yml` — auto-detected by Spring Boot from the PostgreSQL driver |
| `hibernate.jdbc.time_zone` | `UTC` | All timestamps in UTC |

### 3.4 Flyway

| Property | Value | Description |
|---|---|---|
| `spring.flyway.enabled` | `true` | Auto-apply migrations on startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file location |
| `spring.flyway.baseline-on-migrate` | `true` | Baseline existing DBs on first run |
| `spring.flyway.table` | `flyway_schema_history_compliance` | Namespaced history table so each CCE service tracks its own migrations in the shared database |

### 3.5 Observability

| Property | Value | Description |
|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Exposed actuator endpoints |
| `management.tracing.sampling.probability` | `1.0` | 100% trace sampling |
| `management.metrics.tags.application` | `cce-compliance-service` | Common metric tag |

## 4. Project Structure

```
cce-compliance-service/
├── artifacts/                          # Design documents
│   └── CCE Solution Design v0.3 Draft.pdf
├── docs/                               # Documentation (this folder)
│   ├── architecture-overview.md
│   ├── flow-diagrams.md
│   ├── api-reference.md
│   ├── data-dictionary.md
│   ├── kafka-events.md
│   ├── deployment-guide.md
│   ├── sample-plan-definition.md
│   └── developer-setup.md
├── src/
│   └── main/
│       ├── java/org/openphc/cce/compliance/
│       │   ├── ComplianceServiceApplication.java
│       │   ├── config/          # Spring configuration
│       │   ├── domain/          # Entities, enums, repositories
│       │   ├── fhir/            # FHIR parsing, JSONLogic & FHIRPath expression evaluation
│       │   ├── kafka/           # Kafka consumers, models, config
│       │   │   ├── config/      # Consumer/Producer factories, topic bindings
│       │   │   ├── consumer/    # InboundEventConsumer, SchedulerTriggerConsumer
│       │   │   ├── model/       # CloudEventMessage, SchedulerTriggerMessage, IntelligenceTriggerEvent
│       │   │   └── producer/    # IntelligenceTriggerProducer
│       │   ├── service/         # Business logic (ComplianceEngine + supporting services)
│       │   └── web/             # REST controllers, DTOs, exception handler
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               ├── V1__initial_schema.sql
│               ├── V2__intelligence_tables.sql
│               ├── V3__facility.sql
│               ├── V4__state_history.sql
│               ├── V5__deviation_unique_constraint.sql
│               ├── V6__drop_uuid_defaults_for_v7.sql
│               ├── V7__facility_district.sql
│               └── V8__group_step_instance.sql
├── Dockerfile                          # Multi-stage Docker build
├── .gitignore
├── build.gradle                        # Gradle build configuration
└── settings.gradle                     # Gradle settings
```

## 5. Database Setup

All CCE services share the same database (`ccedb`) on the PostgreSQL instance deployed by the CCE Collector Service (port `5433`, user `cce_user`). Each service owns its own tables — Flyway migrations are namespaced to avoid conflicts.

### 5.1 No Separate Database Creation Needed

The database is created by the collector service's Docker Compose. The compliance service only runs its Flyway migrations on startup.

### 5.2 Flyway Migrations

Migrations are applied automatically on application startup via Spring Boot's Flyway autoconfiguration. There is no Flyway Gradle plugin in `build.gradle` (only the `flyway-core` and `flyway-database-postgresql` libraries used at runtime), so `flywayMigrate` / `flywayInfo` Gradle tasks are **not** available. To inspect or run migrations manually, use the Flyway CLI or psql directly against the `flyway_schema_history_compliance` table:

```bash
# Inspect applied migrations directly
psql -h localhost -p 5433 -U cce_user -d ccedb \
     -c "SELECT * FROM flyway_schema_history_compliance ORDER BY installed_rank"
```

### 5.3 Current Migrations

| Version | Description | Script |
|---|---|---|
| V1 | Initial schema (7 tables, indexes, constraints) | `V1__initial_schema.sql` |
| V2 | Intelligence tables (action_definition, intelligence_event_log) | `V2__intelligence_tables.sql` |
| V3 | Facility table | `V3__facility.sql` |
| V4 | Append-only state-transition history for protocol_instance and step_instance | `V4__state_history.sql` |
| V5 | Deduplicate deviations and enforce one deviation per (step, type) | `V5__deviation_unique_constraint.sql` |
| V6 | Drop DB-side UUID (v4) defaults now that ids are generated application-side as UUID v7 | `V6__drop_uuid_defaults_for_v7.sql` |
| V7 | Add `district_name` column to facility | `V7__facility_district.sql` |
| V8 | Repeating step groups: `group_step_instance` table + `step_instance.group_step_instance_id` FK | `V8__group_step_instance.sql` |

## 6. Docker Build

### 6.1 Build Image

```bash
# Build the Docker image
docker build -t cce-compliance-service:latest .

# Run the container
# SERVER_PORT must be set to 8080 to match the image's EXPOSE/healthcheck port
# (the application's own default, when unset, is 8091)
docker run -d \
  --name compliance-service \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e DB_NAME=ccedb \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  cce-compliance-service:latest
```

### 6.2 Dockerfile Overview

```
Stage 1: Build (eclipse-temurin:21-jdk-alpine)
  → Copy build.gradle, settings.gradle, download dependencies
  → Copy source, run ./gradlew build -x test

Stage 2: Runtime (eclipse-temurin:21-jre-alpine)
  → Create non-root user 'cce' (UID 1001)
  → Copy JAR from build stage
  → JVM flags: none baked in — entrypoint runs `java $JAVA_OPTS -jar app.jar`, so flags are supplied via the JAVA_OPTS env var at runtime
  → Healthcheck: wget to /actuator/health every 30s
  → Expose port 8080
```

## 7. Key Build Commands

| Command | Purpose |
|---|---|
| `./gradlew build -x test` | Build without tests |
| `./gradlew build` | Build + run unit tests |
| `./gradlew test` | Run unit tests only |
| `./gradlew integrationTest` | Run integration tests (EmbeddedKafka + H2) |
| `./gradlew test jacocoTestReport` | Unit tests + coverage report |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew bootRun` | Run application via Gradle |

## 8. Testing

### 8.1 Test Dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ |
| `spring-kafka-test` | Kafka test utilities |
| `awaitility` | Async assertions (integration tests only) |

### 8.2 Test Categories

| Category | Location | Infrastructure |
|---|---|---|
| Unit tests | `src/test/java` | Mocked dependencies |
| Integration tests | `src/integrationTest/java` | EmbeddedKafka + H2 in-memory (PostgreSQL mode) |
| API tests | `src/test/java` | MockMvc |

### 8.3 Running Tests

```bash
# Unit tests (424 tests)
./gradlew test

# Integration tests (33 tests — EmbeddedKafka + H2)
./gradlew integrationTest

# Specific test class
./gradlew test --tests ComplianceEngineTest

# Full build + unit tests
./gradlew build

# With test coverage
./gradlew test jacocoTestReport
```

## 9. IDE Setup

### 9.1 IntelliJ IDEA

1. Import as Gradle project
2. Set JDK to 21
3. Enable annotation processing (required for Lombok, used throughout the domain/DTO/service layers)
4. Configure Spring Boot run configuration:
   - Main class: `org.openphc.cce.compliance.ComplianceServiceApplication`
   - Active profiles: `local` (if needed)
   - Environment variables: as listed in Section 3.1

### 9.2 VS Code

1. Install "Extension Pack for Java" and "Spring Boot Extension Pack"
2. Open the project folder
3. VS Code auto-detects the Gradle project
4. Use the Spring Boot Dashboard to run/debug

## 10. Logging

### 10.1 Log Format

```
2026-03-15 10:30:00.123 [kafka-consumer-1] [corr-abc123] INFO ComplianceEngine - Processing inbound event...
```

Format: `timestamp [thread] [correlationId] level logger - message`

### 10.2 Log Levels

| Logger | Default Level | Description |
|---|---|---|
| `org.openphc.cce.compliance` | `INFO` | Application logs |
| `org.springframework.kafka` | `WARN` | Kafka framework logs |
| `org.hibernate.SQL` | `WARN` | SQL statement logs |

### 10.3 Adjusting Log Levels

```bash
# Via environment variable
LOGGING_LEVEL_ORG_OPENPHC_CCE_COMPLIANCE=DEBUG java -jar build/libs/cce-compliance-service-1.0.0.jar

# Via application.yml override
# logging.level.org.openphc.cce.compliance: DEBUG
```

## 11. Troubleshooting

### 11.1 Common Issues

| Issue | Cause | Solution |
|---|---|---|
| `Connection refused: localhost:5433` | PostgreSQL not running | Start collector service infrastructure: `cd cce-collector-service && docker compose up -d` |
| `Connection refused: localhost:9092` | Kafka not running | Start Kafka or Docker container |
| `401 Unauthorized` on API calls | Authentication handled by gateway | Ensure requests come through the API gateway |
| `Flyway migration failed` | Schema conflicts | Check migration scripts; there is no Flyway Gradle plugin in this project, so reset manually (dev only) by dropping the affected tables and `flyway_schema_history_compliance` rows via psql |
| `Deserialization error` | Message format mismatch | Check producer serialization, trusted packages |
| Build fails with `javac not found` | JDK not installed (JRE only) | Install JDK 21 or use Docker build |

### 11.2 Useful Diagnostic Commands

```bash
# Check application health (default local port is 8091 unless SERVER_PORT is overridden)
curl -s http://localhost:8091/actuator/health | jq .

# View application metrics
curl -s http://localhost:8091/actuator/metrics | jq .

# Check specific metric
curl -s http://localhost:8091/actuator/metrics/cce.events.processed | jq .

# View Prometheus metrics
curl http://localhost:8091/actuator/prometheus

# Check database connectivity
psql -h localhost -p 5433 -U cce_user -d ccedb -c "SELECT 1"

# Check Kafka topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group cce-compliance-service --describe
```

## 12. Security Notes for Development

Authentication and authorization are handled by the **CCE API Gateway**. This service does not implement security directly — all requests are expected to arrive pre-authenticated through the gateway.

For local development, requests can be made directly to the service without authentication.
