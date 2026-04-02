# Deployment Guide

> **CCE Compliance Service** — Production deployment reference  
> **Version:** 1.0.0 | **Java:** 21 LTS | **Spring Boot:** 3.4.2

---

## Table of Contents

1. [Infrastructure Requirements](#1-infrastructure-requirements)
2. [Environment Variables](#2-environment-variables)
3. [Docker Deployment](#3-docker-deployment)
4. [Kubernetes Deployment](#4-kubernetes-deployment)
5. [Database Setup](#5-database-setup)
6. [Kafka Setup](#6-kafka-setup)
7. [JVM Tuning](#7-jvm-tuning)
8. [Health Checks & Monitoring](#8-health-checks--monitoring)
9. [Backup & Recovery](#9-backup--recovery)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Infrastructure Requirements

| Component | Version | Purpose | Notes |
|---|---|---|---|
| **Java JDK** | 21 LTS | Runtime | Eclipse Temurin recommended |
| **PostgreSQL** | 16+ | Primary database | JSONB support required |
| **Apache Kafka** | 3.x | Message broker | KRaft mode (no Zookeeper) |
| **Docker** | 24+ | Container runtime | Optional if running natively |

### Resource Recommendations (Production)

| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 2 cores | 4 cores |
| Memory | 1 GB | 2 GB |
| Disk (app) | 500 MB | 1 GB |
| Disk (PostgreSQL) | 10 GB | 50 GB+ |
| Network | 100 Mbps | 1 Gbps |

---

## 2. Environment Variables

All configuration is externalized via environment variables. Defaults are provided for local development.

### Application

| Variable | Default | Required | Description |
|---|---|---|---|
| `SERVER_PORT` | `8080` | No | HTTP server port |
| `SPRING_PROFILES_ACTIVE` | — | Yes (prod) | Set to `prod` for production |

### Database

| Variable | Default | Required | Description |
|---|---|---|---|
| `DB_HOST` | `localhost` | Yes | PostgreSQL host (shared with collector service) |
| `DB_PORT` | `5433` | No | PostgreSQL port (collector service default) |
| `DB_NAME` | `cce_collector` | No | Shared database name (all CCE services) |
| `DB_USERNAME` | `cce_user` | Yes | Database username (shared with collector service) |
| `DB_PASSWORD` | `cce_pass` | Yes | Database password (shared with collector service) |
| `DB_POOL_SIZE` | `20` (dev) / `30` (prod) | No | HikariCP maximum pool size |
| `DB_POOL_MIN_IDLE` | `5` (dev) / `10` (prod) | No | HikariCP minimum idle connections |
| `DB_CONNECTION_TIMEOUT` | `30000` | No | Connection timeout (ms) |
| `DB_IDLE_TIMEOUT` | `600000` | No | Idle connection timeout (ms) |
| `DB_MAX_LIFETIME` | `1800000` | No | Max connection lifetime (ms) |

### Kafka

| Variable | Default | Required | Description |
|---|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Yes | Kafka bootstrap servers |
| `KAFKA_CONCURRENCY` | `3` (dev) / `5` (prod) | No | Consumer listener concurrency |
| `KAFKA_PARTITIONS` | `25` | No | Default partition count for topics |
| `KAFKA_RETRY_MAX_ATTEMPTS` | `3` (dev) / `5` (prod) | No | Max retry attempts before DLQ |
| `KAFKA_RETRY_BACKOFF_MS` | `1000` (dev) / `2000` (prod) | No | Backoff interval between retries |

### Observability

| Variable | Default | Required | Description |
|---|---|---|---|
| `TRACING_SAMPLE_RATE` | `1.0` (dev) / `0.1` (prod) | No | Distributed tracing sample rate |

---

## 3. Docker Deployment

### Build the Image

```bash
docker build -t cce-compliance-service:1.0.0 .
```

### Run with Docker

```bash
docker run -d \
  --name cce-compliance-service \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST=postgres-host \
  -e DB_PORT=5433 \
  -e DB_NAME=cce_collector \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=cce_pass \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka-host:9092 \
  cce-compliance-service:1.0.0
```

### Docker Compose (Local Development)

PostgreSQL, Kafka, and the shared database are deployed by the **CCE Collector Service**. Start the collector infrastructure, then run the compliance service:

```bash
# 1. Start shared infrastructure (PostgreSQL on port 5433 + Kafka on port 9092)
cd /path/to/cce-collector-service
docker compose up -d

# 2. Run the compliance service (Flyway applies schema migrations automatically)
cd /path/to/cce-compliance-service
./gradlew bootRun
```

The shared infrastructure (from [cce-collector-service deployment guide](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md)) provides:
- **PostgreSQL 16** on port `5433` (user: `cce_user`, password: `cce_pass`, database: `cce_collector`)
- **Apache Kafka 3.7.0 KRaft** on port `9092` (single broker, no Zookeeper)

> **Note:** All CCE services share the same `cce_collector` database. Each service owns its own tables — Flyway migrations are namespaced to avoid conflicts.

---

## 4. Kubernetes Deployment

### Example Deployment Manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cce-compliance-service
  labels:
    app: cce-compliance-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: cce-compliance-service
  template:
    metadata:
      labels:
        app: cce-compliance-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8080"
    spec:
      containers:
        - name: cce-compliance-service
          image: cce-compliance-service:1.0.0
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: DB_HOST
              valueFrom:
                configMapKeyRef:
                  name: cce-config
                  key: db-host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: cce-secrets
                  key: db-password
            - name: KAFKA_BOOTSTRAP_SERVERS
              valueFrom:
                configMapKeyRef:
                  name: cce-config
                  key: kafka-bootstrap-servers
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          resources:
            requests:
              cpu: "500m"
              memory: "512Mi"
            limits:
              cpu: "2000m"
              memory: "2Gi"
```

---

## 5. Database Setup

All CCE services share the same PostgreSQL database (`cce_collector`) deployed by the [CCE Collector Service](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md). Each service owns its own tables within the shared database — Flyway migrations are namespaced to avoid conflicts.

### Shared Infrastructure

| Property | Value |
|---|---|
| **PostgreSQL Host** | Same as collector service |
| **PostgreSQL Port** | `5433` (local dev) / as configured (production) |
| **Shared User** | `cce_user` |
| **Shared Database** | `cce_collector` |

### No Separate Database Creation Needed

The database and user are created by the collector service's Docker Compose. The compliance service only needs to run its Flyway migrations, which happen automatically on startup.

> **Compliance service tables:** `protocol_definition`, `protocol_instance`, `step_instance`, `deviation`, `trigger_index`, `event_log`, `audit_log`, `action_definition`, `action_run`

### Schema Migrations

Flyway manages all schema migrations automatically on application startup.

- Migrations are located at `classpath:db/migration`
- `V1__initial_schema.sql` creates all 7 tables with indexes and constraints
- `ddl-auto=validate` ensures Hibernate validates entity mappings against the actual schema
- **Production:** Set `spring.flyway.baseline-on-migrate=false` (default in prod profile)

### Connection Pool Sizing

The HikariCP pool size should account for:
- Kafka consumer threads (concurrency setting, default 5 in prod)
- REST thread pool (Tomcat default 200, typically ~20 active)
- Async audit service threads

**Formula:** `maximumPoolSize ≥ (kafka_concurrency × 2) + 15`

With default production settings (concurrency=5): `30` connections is appropriate.

---

## 6. Kafka Setup

The CCE Compliance Service shares the Kafka cluster deployed by the [CCE Collector Service](https://github.com/Jayaprakash8887/cce-collector-service/blob/release-1.0.0/docs/deployment-guide.md). The collector publishes to `cce.events.inbound`, which this service consumes.

### Topics

The service auto-creates topics on startup via `KafkaAdmin` + `NewTopic` beans. For production, pre-create topics with appropriate replication:

```bash
# Primary topics
kafka-topics.sh --create --topic cce.events.inbound \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

kafka-topics.sh --create --topic cce.scheduler.triggers \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

kafka-topics.sh --create --topic cce.intelligence.triggers \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

# DLQ topics
kafka-topics.sh --create --topic cce.events.inbound.dlq \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092

kafka-topics.sh --create --topic cce.scheduler.triggers.dlq \
  --partitions 25 --replication-factor 3 --bootstrap-server kafka:9092
```

### Consumer Group

- **Group ID:** `cce-compliance-service`
- **Auto offset reset:** `earliest`
- **Isolation level:** `read_committed`
- **Max poll records:** 200 (prod)

### Error Handling

Failed messages are retried with `FixedBackOff` (5 attempts × 2s interval in prod), then routed to the corresponding `.dlq` topic. Monitor DLQ topics for persistent failures.

---

## 7. JVM Tuning

The Dockerfile includes container-aware JVM flags:

```
JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC
```

### Recommended Production Flags

```bash
JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -Djava.security.egd=file:/dev/./urandom"
```

| Flag | Purpose |
|---|---|
| `UseContainerSupport` | Respect container memory/CPU limits |
| `MaxRAMPercentage=75.0` | Use 75% of container memory for heap |
| `UseG1GC` | G1 garbage collector (recommended for services) |
| `MaxGCPauseMillis=200` | Target max GC pause time |
| `UseStringDeduplication` | Reduce memory for duplicate strings (FHIR payloads) |

---

## 8. Health Checks & Monitoring

### Health Endpoints

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall application health |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/actuator/info` | Application info |
| `/actuator/metrics` | Micrometer metrics browser |

### Key Metrics to Monitor

| Metric | Type | Alert Threshold |
|---|---|---|
| `cce.events.processed` | Counter | Sudden drop = consumer issue |
| `cce.events.duplicate` | Counter | High rate = upstream replay |
| `cce.events.zero_match` | Counter | High rate = missing protocols |
| `cce.events.processing.duration` | Timer | p99 > 500ms |
| `cce.step.matching.duration` | Timer | p99 > 200ms |
| `cce.protocol.instances.active` | Gauge | Abnormal growth |
| `hikaricp.connections.active` | Gauge | Near pool max |
| `kafka.consumer.fetch.manager.records.lag` | Gauge | Growing lag |

### Prometheus Scrape Configuration

```yaml
scrape_configs:
  - job_name: 'cce-compliance-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['cce-compliance-service:8080']
        labels:
          application: 'cce-compliance-service'
```

### Recommended Grafana Dashboards

- **Spring Boot Statistics** — JVM, HTTP, HikariCP, Tomcat metrics
- **Kafka Consumer Lag** — Consumer group lag monitoring
- **Custom CCE Dashboard** — Event processing rates, matching durations, active instances

---

## 9. Backup & Recovery

### Database Backup

```bash
# Full backup
pg_dump -U cce_user -h postgres-host -p 5433 cce_collector > backup_$(date +%Y%m%d).sql

# Restore
psql -U cce_user -h postgres-host -p 5433 cce_collector < backup_20250101.sql
```

### Recovery Considerations

- **Kafka offsets:** Consumer group offsets are stored in Kafka. On restart, processing resumes from the last committed offset.
- **Idempotency:** The `(cloudeventsId, source)` unique constraint on `event_log` ensures safe event reprocessing.
- **Protocol definitions:** Stored in PostgreSQL with JSONB. Trigger index can be rebuilt via the `/v1/protocol-definitions/{id}/rebuild-index` endpoint.
- **Condition-only triggers:** Loaded in-memory from stored definitions on startup (rebuild-index).

---

## 10. Troubleshooting

### Common Issues

| Problem | Possible Cause | Resolution |
|---|---|---|
| Service won't start | Database unreachable | Check `DB_HOST`, `DB_PORT`, network connectivity |
| Flyway migration fails | Schema already exists | Check `baseline-on-migrate` setting |
| No events processed | Kafka unreachable | Check `KAFKA_BOOTSTRAP_SERVERS`, broker health |
| Events going to DLQ | Deserialization errors | Check message format matches CloudEvents schema |
| High consumer lag | Slow processing | Increase `KAFKA_CONCURRENCY`, check DB performance |
| Connection pool exhaustion | Too many concurrent requests | Increase `DB_POOL_SIZE` |

### Log Configuration

Production logging levels (in `application-prod.yml`):
```yaml
logging:
  level:
    root: WARN
    org.openphc.cce.compliance: INFO
    org.apache.kafka: ERROR
    org.hibernate: ERROR
```

To enable debug logging for specific components temporarily:
```bash
-Dlogging.level.org.openphc.cce.compliance.service.ComplianceEngine=DEBUG
```
