# Developer Setup & Configuration

## 1. Prerequisites

> **Full technology stack:** See [architecture-overview.md §2](architecture-overview.md#2-technology-stack) for the complete technology stack with versions and purpose.

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

PostgreSQL, Kafka, and the shared database (`cce_collector`) are deployed by the **CCE Collector Service**. All CCE services share the same database.

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
# Health check
curl http://localhost:8080/actuator/health

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
| `DB_PORT` | `5433` | PostgreSQL port (shared with collector service) |
| `DB_NAME` | `cce_collector` | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `20` | HikariCP max pool size |

#### Kafka

| Variable | Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses |

#### Server

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Application port |

### 3.2 Kafka Topic Configuration

Topic names are configured via `cce.kafka.topics.*` in `application.yml`. The service auto-creates 5 topics on startup (3 primary + 2 DLQ, 25 partitions each).

> **Complete topic inventory, consumer/producer configuration, and retry policies:** See [kafka-events.md](kafka-events.md).

### 3.3 JPA & Hibernate

| Property | Value | Description |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema managed by Flyway; Hibernate only validates |
| `spring.jpa.open-in-view` | `false` | Prevents lazy loading in controllers (best practice) |
| `hibernate.dialect` | `PostgreSQLDialect` | PostgreSQL-specific SQL generation |
| `hibernate.jdbc.time_zone` | `UTC` | All timestamps in UTC |

### 3.4 Flyway

| Property | Value | Description |
|---|---|---|
| `spring.flyway.enabled` | `true` | Auto-apply migrations on startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file location |
| `spring.flyway.baseline-on-migrate` | `true` | Baseline existing DBs on first run |

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
│   └── developer-setup.md
├── src/
│   └── main/
│       ├── java/org/openphc/cce/compliance/
│       │   ├── ComplianceServiceApplication.java
│       │   ├── config/          # Spring configuration
│       │   ├── domain/          # Entities, enums, repositories
│       │   ├── fhir/            # FHIR parsing, JSONLogic & FHIRPath expression evaluation
│       │   ├── kafka/           # Kafka consumers, producers, models, config
│       │   │   ├── config/      # Consumer/Producer factories, topic bindings
│       │   │   ├── consumer/    # InboundEventConsumer, SchedulerTriggerConsumer
│       │   │   ├── model/       # CloudEventMessage, SchedulerTriggerMessage, IntelligenceTriggerEvent
│       │   │   └── producer/    # IntelligenceTriggerProducer
│       │   ├── service/         # Business logic (ComplianceEngine + supporting services)
│       │   └── web/             # REST controllers, DTOs, exception handler
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__initial_schema.sql
├── Dockerfile                          # Multi-stage Docker build
├── .gitignore
├── build.gradle                        # Gradle build configuration
└── settings.gradle                     # Gradle settings
```

## 5. Database Setup

All CCE services share the same database (`cce_collector`) on the PostgreSQL instance deployed by the CCE Collector Service (port `5433`, user `cce_user`). Each service owns its own tables — Flyway migrations are namespaced to avoid conflicts.

### 5.1 No Separate Database Creation Needed

The database is created by the collector service's Docker Compose. The compliance service only runs its Flyway migrations on startup.

### 5.2 Flyway Migrations

Migrations are applied automatically on application startup. To run manually:

```bash
# Using Gradle Flyway plugin (if configured)
./gradlew flywayMigrate -Dflyway.url=jdbc:postgresql://localhost:5433/cce_collector \
                        -Dflyway.user=cce_user \
                        -Dflyway.password=cce_pass

# Check migration status
./gradlew flywayInfo
```

### 5.3 Current Migrations

| Version | Description | Script |
|---|---|---|
| V1 | Initial schema | `V1__initial_schema.sql` |
| V2 | Intelligence tables | `V2__intelligence_tables.sql` |
| V3 | Performance indexes | `V3__performance_indexes.sql` |

## 6. Docker Build

### 6.1 Build Image

```bash
# Build the Docker image
docker build -t cce-compliance-service:latest .

# Run the container
docker run -d \
  --name compliance-service \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e DB_NAME=cce_collector \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  cce-compliance-service:latest
```

### 6.2 Dockerfile Overview

```
Stage 1: Build (eclipse-temurin:21-jdk-alpine)
  → Copy build.gradle, settings.gradle, download dependencies
  → Copy source, run ./gradlew build

Stage 2: Runtime (eclipse-temurin:21-jre-alpine)
  → Create non-root user 'cce' (UID 1001)
  → Copy JAR from build stage
  → JVM flags: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC
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

### 8.2 Test Categories

| Category | Location | Infrastructure |
|---|---|---|
| Unit tests | `src/test/java` | Mocked dependencies |
| Integration tests | `src/integrationTest/java` | EmbeddedKafka + H2 in-memory (PostgreSQL mode) |
| API tests | `src/test/java` | MockMvc |

### 8.3 Running Tests

```bash
# Unit tests (258 tests)
./gradlew test

# Integration tests (24 tests — EmbeddedKafka + H2)
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
3. Enable annotation processing (for Lombok if added later)
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
LOGGING_LEVEL_ORG_OPENPHC_CCE_COMPLIANCE=DEBUG java -jar target/*.jar

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
| `Flyway migration failed` | Schema conflicts | Check migration scripts, reset with `flyway:clean` (dev only) |
| `Deserialization error` | Message format mismatch | Check producer serialization, trusted packages |
| Build fails with `javac not found` | JDK not installed (JRE only) | Install JDK 21 or use Docker build |

### 11.2 Useful Diagnostic Commands

```bash
# Check application health
curl -s http://localhost:8080/actuator/health | jq .

# View application metrics
curl -s http://localhost:8080/actuator/metrics | jq .

# Check specific metric
curl -s http://localhost:8080/actuator/metrics/cce.events.processed | jq .

# View Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Check database connectivity
psql -h localhost -p 5433 -U cce_user -d cce_collector -c "SELECT 1"

# Check Kafka topics
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group cce-compliance-service --describe
```

## 12. Security Notes for Development

Authentication and authorization are handled by the **CCE API Gateway**. This service does not implement security directly — all requests are expected to arrive pre-authenticated through the gateway.

For local development, requests can be made directly to the service without authentication.
