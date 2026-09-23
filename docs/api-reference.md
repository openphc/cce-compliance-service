# API Reference — Compliance Service

Base URL: `http://<host>:8092`

This service has a **read-only** HTTP surface. Everything it writes is driven by its scheduler, not by
a request — see [Architecture §3](architecture-overview.md#3-the-fetch-and-apply-cycle).

Error bodies and status codes come from the shared `GlobalExceptionHandler`:
[Library Reference §5](../../cce-common-util/docs/library-reference.md#5-exception).

---

## Intelligence events

`/v1/compliance/intelligence-events`

A record of every intelligence action evaluated — what fired, why, against which step, and whether the
trigger reached the broker.

### `GET /v1/compliance/intelligence-events`

Paged. Standard Spring `Pageable` (`?page=0&size=20&sort=createdAt,desc`).

| Parameter | Effect |
|---|---|
| `protocolInstanceId` | events for one patient enrolment |
| `actionDefinitionId` | events for one action definition — "who did this alert go to, and how often" |
| `published` | `false` surfaces triggers the broker never acknowledged |
| none | all events |

The filters are **mutually exclusive**, applied in the order listed. Passing
`protocolInstanceId` and `published` together silently applies only `protocolInstanceId`; it is not an
error. Combine filters client-side if you need an intersection.

`?published=false` is the operationally interesting one: it lists intelligence that was evaluated and
should have been delivered but was not confirmed by Kafka. Those rows are the replay candidates.

### `GET /v1/compliance/intelligence-events/{id}`

One event. `404` if no such id.

### Response

```json
{
  "id": "018f2c1a-...",
  "subject": "260225-0002-5501",
  "protocolInstanceId": "018f2c19-...",
  "stepInstanceId": "018f2c1b-...",
  "stepActionId": "bp-check",
  "deviationId": "018f2c1c-...",
  "actionDefinitionId": "018f2c10-...",
  "actionType": "CommunicationRequest",
  "intelligenceDestination": "supervisor",
  "stepStatus": "not-started",
  "slaStatus": "overdue",
  "triggerReason": "overdue",
  "evaluationExpression": "{\">\": [{\"var\": \"riskScore\"}, 7]}",
  "evaluationContext": { "riskScore": 9 },
  "eventPayload": { "...": "the published trigger" },
  "published": true,
  "publishedAt": "2026-08-18T09:15:02Z",
  "createdAt": "2026-08-18T09:15:00Z"
}
```

Notes on reading a row:

- `stepStatus` and `slaStatus` are the **FHIR codes** (`not-started`, `overdue`), and they are a
  *snapshot at evaluation time* — not a live view of the step, which may have progressed since.
- `triggerReason` is the lowercased deviation type, or `completion`. Rows written by *this* service
  are always `overdue` or `missed`; `order_violation` and `completion` come from the Matcher Service,
  which writes to the same table.
- `deviationId` is set when the evaluation was triggered by a deviation and null when it was triggered
  by a step completion.
- `evaluationContext` is what the expression was evaluated against. With `evaluationExpression`, it is
  enough to reproduce a firing decision after the fact — which is the reason both are stored rather
  than just the outcome.
- `published: false` with a non-null `eventPayload` means the trigger was built and the send was not
  confirmed. The payload is retained precisely so it can be replayed.

`intelligence_event_log` is deliberately flat, with no foreign keys — the ids are recorded as values so
a row survives the deletion of anything it references. Column detail:
[Data Dictionary §11](../../cce-common-util/docs/data-dictionary.md#11-intelligence_event_log).

---

## Operational endpoints

| Path | Purpose |
|---|---|
| `/actuator/health` | Liveness and readiness probes |
| `/actuator/health/readiness` | Fails while the database is unreachable |
| `/actuator/info` | Build info |
| `/actuator/metrics` | Micrometer metrics |
| `/actuator/prometheus` | Prometheus scrape |

The SLA sweep is not exposed over HTTP — it cannot be triggered, paused or drained by a request. Its
state is observable through the metrics in
[Architecture §6](architecture-overview.md#6-observability) and through
`step_sla_state_transition` itself.
