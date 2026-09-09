# Deployment Guide — Compliance Service

Deploy **last**. This service creates no tables and validates its JPA mapping at startup, so it will
fail fast against a `ccedb` the other two services have not yet migrated. Ordering rationale:
[Architecture Overview §6](../../cce-common-util/docs/architecture-overview.md#6-deployment-order).

## Requirements

| Component | Requirement |
|---|---|
| JRE | 21 |
| PostgreSQL | 16, database `ccedb` — schema already applied by the Protocol and Matcher services |
| Kafka | producer only — `cce.intelligence.triggers` must exist or be auto-creatable |
| Memory | 1 GB heap is comfortable |

The database user needs **no DDL rights**. If it has them, that is a wider grant than this service
requires.

## Environment variables

Full list with defaults: [Developer Setup](developer-setup.md#configuration). The ones that matter in
production:

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | |
| `DB_USERNAME` / `DB_PASSWORD` | `cce_user` / `cce_pass` | never leave at the default |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `CCE_SLA_INSTANCE_ID` | `$HOSTNAME` | **set this per replica** — it lands in `processed_by` |
| `CCE_SLA_POLL_INTERVAL_MS` | `5000` | |
| `CCE_SLA_BATCH_SIZE` | `100` | |

`CCE_SLA_INSTANCE_ID` defaults to `$HOSTNAME`, which is already distinct per pod in Kubernetes. Set it
explicitly anywhere hostnames are not unique, or `processed_by` stops being able to identify a
misbehaving replica.

## Docker

**Build from the workspace directory, not from this repository.** This service depends on
`cce-common-util` as a Gradle composite build, and Docker's `COPY` cannot reach outside its build
context:

```bash
cd ..            # the directory containing cce-compliance-service and cce-common-util
docker build -f cce-compliance-service/Dockerfile -t cce-compliance-service:2.0.0 .
```

```bash
docker run -d --name cce-compliance-service \
  -p 8092:8080 \
  -e DB_HOST=postgres-host -e DB_PORT=5433 \
  -e DB_USERNAME=cce_user -e DB_PASSWORD='<secret>' \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka-host:9092 \
  -e CCE_SLA_INSTANCE_ID=compliance-1 \
  cce-compliance-service:2.0.0
```

The image pins `SERVER_PORT=8080` to match its `EXPOSE` and healthcheck; the application's own default
outside Docker is `8092`.

## Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cce-compliance-service
spec:
  replicas: 2
  selector:
    matchLabels: { app: cce-compliance-service }
  template:
    metadata:
      labels: { app: cce-compliance-service }
    spec:
      containers:
        - name: cce-compliance-service
          image: cce-compliance-service:2.0.0
          ports: [{ containerPort: 8080 }]
          env:
            - name: CCE_SLA_INSTANCE_ID
              valueFrom: { fieldRef: { fieldPath: metadata.name } }
            - name: DB_HOST
              value: postgres.cce.svc.cluster.local
            - { name: DB_USERNAME, valueFrom: { secretKeyRef: { name: cce-db, key: username } } }
            - { name: DB_PASSWORD, valueFrom: { secretKeyRef: { name: cce-db, key: password } } }
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: kafka.cce.svc.cluster.local:9092
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 40
          resources:
            requests: { memory: 1Gi, cpu: 500m }
            limits:   { memory: 2Gi, cpu: "2" }
```

**Multiple replicas are safe and useful.** The fetch-and-apply cycle needs no coordination — no leader
election, no lease, no partition assignment — so an added replica adds throughput directly. This
is unlike the Matcher Service, whose parallelism is bounded by Kafka partitions.

Scale on the `cce.sla.transitions.due` gauge rather than on CPU: this service is
database-bound, and a backlog is visible in that gauge long before it shows up as CPU pressure.

## Kafka

Produce-only. One topic:

| Topic | Direction |
|---|---|
| `cce.intelligence.triggers` | produce |

No consumer group, no DLQ, no inbound topic. If you find a consumer group named after this service on
the broker, it is a leftover from the pre-split monolith and can be deleted.

## Event Replay — sequencing the two services

**Event Replay** is any run where the Matcher Service has a backlog of events on `cce.events.inbound`
to work through: events re-published after a fix, a historical backfill during migration, or a long
outage that left its consumer group far behind.

**This service must be stopped for the duration.** Running it against an unmatched backlog makes it
record `OVERDUE` and `MISSED` against steps whose completing event has not been processed yet — and
neither those verdicts nor the clinician alerts they trigger can be withdrawn. The mechanism, and why
stopping costs nothing, is in
[Architecture — Operational prerequisite](architecture-overview.md#operational-prerequisite--event-replay).

### Procedure

**1. Stop this service.**

```bash
kubectl scale deployment/cce-compliance-service --replicas=0
```

There is no in-process pause switch — the `@Scheduled` poll has no guard — so scaling to zero (or
stopping the container) is the only way to hold the sweep.

**2. Let the Matcher Service drain.** Watch its consumer group until every partition reads `LAG 0`:

```bash
kafka-consumer-groups.sh --bootstrap-server "$BROKER" \
  --group cce-matcher-service --describe
```

The group commits offsets only after a record has been processed (`enable-auto-commit: false`), so
`LAG 0` means the database writes for those events are committed — not merely that the records were
read. Wait a few minutes at zero before continuing; a late burst is easy to miss.

**3. Start this service.**

```bash
kubectl scale deployment/cce-compliance-service --replicas=1
```

**4. Watch the drain.** `cce.sla.transitions.due` starts high — every deadline that fell during the
replay is due at once — and should fall towards zero within a few cycles, with
`cce.sla.evaluator.batches.failed` flat. Batches drain within a cycle, so even a large backlog clears
in minutes rather than one batch per poll interval.

### If the sequence was missed

There is no automatic correction, and it is worth being blunt about what that means:

| Already written | Recoverable? |
|---|---|
| `sla_status` = `OVERDUE` / `MISSED` on a step that was on time | Only by a manual data fix — the service will not revise it, since writes are forward-only and `MET` is written only over a null |
| The `OVERDUE` / `MISSED` deviation row | Only by deleting it manually |
| The intelligence event on `cce.intelligence.triggers` | **No.** It has been delivered |

This query finds the affected steps — ones whose recorded breach disagrees with the `completed_at`
that arrived afterwards:

```sql
SELECT s.id, s.action_id, s.sla_status, s.completed_at,
       t.transition_type, t.process_by, t.processed_at
FROM step_instance s
JOIN step_sla_state_transition t ON t.step_instance_id = s.id
WHERE s.step_status = 'COMPLETED'
  AND t.is_processed = true
  AND ( (s.sla_status = 'OVERDUE' AND t.transition_type = 'DUE_DATE_REACHED')
     OR (s.sla_status = 'MISSED'  AND t.transition_type = 'MISSED_DATE_REACHED') )
  AND s.completed_at < t.process_by
ORDER BY t.processed_at DESC;
```

Pairing each status with the row that produced it is what keeps the result clean: without it, a step
legitimately `OVERDUE` for completing late would also match, because its missed-date row was kept.

A row here means the verdict was reached before the completion was known. It is not proof of a missed
Event Replay — any backdated event arriving after its deadline produces the same shape — but after a
replay this is the list to work from. What to do about an alert already sent is a clinical call, not a
technical one.

## Health checks & monitoring

| Endpoint | Use |
|---|---|
| `/actuator/health/readiness` | Route traffic — fails while the database is unreachable |
| `/actuator/health/liveness` | Restart decisions |
| `/actuator/prometheus` | Scrape target |

Note what readiness does **not** cover: the scheduler. A pod can be ready and serving the read API
while its SLA sweep is stalled. The metrics to alert on are `cce.sla.transitions.due` and
`cce.sla.steps.on-time-unsettled` — see
[Architecture §6](architecture-overview.md#6-observability) for how to read them alongside
`evaluator.cycles` and `batches.failed`.

**The two sweeps fail independently, and only one of them is counted.** `poll()` runs the breach sweep
and the on-time sweep in separate `try`/`catch` blocks, deliberately, so a failure in one cannot stop
the other. But `evaluator.cycles` and `evaluator.batches.failed` are incremented by the breach sweep
alone. An on-time sweep that throws on every cycle therefore leaves `cycles` climbing normally,
`batches.failed` flat and `transitions.due` at zero, while no completion is ever recorded `MET`.
`cce.sla.steps.on-time-unsettled` rising is the only signal there is, which is why it belongs on the
alert list and not just on a dashboard.

Suggested alerts:

| Condition | Meaning |
|---|---|
| `cce.sla.transitions.due` rising for > 15 min | the breach sweep is not keeping up |
| `cce.sla.steps.on-time-unsettled` rising for > 15 min | on-time completions are not being recorded `MET` — the second sweep is stalled |
| `cce.sla.evaluator.batches.failed` increasing | rows are failing and backing off |
| `cce.sla.evaluator.cycles` flat | the scheduler thread has stopped — liveness will not catch this |

## Backup

This service owns no tables, so there is nothing here to back up. `step_sla_state_transition`,
`deviation` and `intelligence_event_log` are covered by the Matcher Service's backup.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Startup fails: schema validation error | Deployed out of order — the Protocol and Matcher services must migrate `ccedb` first |
| `due` gauge rising, `cycles` incrementing | Sweep running but not keeping up — add replicas or raise `batch-size` |
| `due` rising, `batches.failed` rising | Rows failing and backing off; check the logs for the rolled-back batch |
| `cycles` not incrementing | Scheduler stopped; restart the pod. Liveness will not detect this |
| Deviations recorded but no intelligence delivered | Check `?published=false` on the [read API](api-reference.md#get-v1complianceintelligence-events) — the trigger may be built but unconfirmed |
| The same alert delivered repeatedly | A transition retrying against an already-recorded deviation should be de-duplicated ([Architecture §5](architecture-overview.md#5-intelligence-on-deviation)); check `attempts` on the row |
| A completed step is `OVERDUE` / `MISSED` although its `completed_at` beat the threshold | The row was judged before the Matcher Service had matched the completing event — the [Event Replay](#event-replay--sequencing-the-two-services) sequence was not held. Not self-correcting |
| A step's `sla_status` looks wrong for a completed step | This service is its **only** writer — Matcher records `step_status` and `completed_at` and never judges timeliness. Compare `completed_at` against the row's `process_by` ([Architecture §4](architecture-overview.md#4-what-the-applier-does)) |
| A completed step stays at a null `sla_status` | It has no `due_date`, so nothing judges it: `MET` requires a deadline to have been beaten. Null is terminal here and correct |
| A settled step still has an unprocessed `MISSED_DATE_REACHED` row | Expected, not a stuck row. A row is taken when its own deadline arrives, so a step completed before its missed date keeps that row until the date passes — then it is consumed and records nothing |
| `on-time-unsettled` rising while `due` sits at zero | The on-time sweep is failing; look for `On-time settlement cycle failed` in the logs. The breach sweep is unaffected, so `cycles` and `batches.failed` look healthy |
