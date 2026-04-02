# API Reference

## Overview

The CCE Compliance Service exposes a RESTful API under the `/v1/` prefix. Authentication is handled by the **CCE API Gateway** — this service receives pre-authenticated requests.

**Base URL:** `http://localhost:8080/v1`

## Authentication

Authentication and authorization are enforced at the API Gateway level. This service does not validate tokens directly. For local development, requests can be made without authentication.

---

## 1. Protocol Definitions

Manage FHIR R4 PlanDefinition resources as protocol definitions.

### 1.1 Load Protocol Definition

**`POST /v1/protocol-definitions`** — Load a new clinical protocol definition.


**Request Body:**

```json
{
  "planDefinitionJson": "{\"resourceType\":\"PlanDefinition\",\"url\":\"http://example.org/PlanDefinition/hiv-treatment\",\"version\":\"1.0\",\"status\":\"active\",\"action\":[{\"id\":\"viral-load-check\",\"trigger\":[{\"type\":\"data-added\",\"data\":[{\"type\":\"Observation\",\"codeFilter\":[{\"path\":\"code\",\"code\":[{\"system\":\"http://loinc.org\",\"code\":\"25836-8\"}]}]}]}],\"condition\":[{\"kind\":\"applicability\",\"expression\":{\"language\":\"text/jsonlogic\",\"expression\":\"{\\\">=\\\":[{\\\"var\\\":\\\"event.valueQuantity.value\\\"},1000]}\"}}]}]}"
}
```

**Response:** `201 Created`

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "url": "http://example.org/PlanDefinition/hiv-treatment",
  "version": "1.0",
  "canonical": "http://example.org/PlanDefinition/hiv-treatment|1.0",
  "status": "active",
  "loadedAt": "2026-03-15T10:30:00Z",
  "definition": { ... }
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `400 Bad Request` | JSON is empty, malformed, or url+version already exists |
| `422 Unprocessable Entity` | FHIR validation errors |

---

### 1.2 List Active Protocol Definitions

**`GET /v1/protocol-definitions`** — List all active protocol definitions.


**Response:** `200 OK`

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "url": "http://example.org/PlanDefinition/hiv-treatment",
    "version": "1.0",
    "canonical": "http://example.org/PlanDefinition/hiv-treatment|1.0",
    "status": "active",
    "loadedAt": "2026-03-15T10:30:00Z",
    "definition": { ... }
  }
]
```

---

### 1.3 Get Protocol Definition by ID

**`GET /v1/protocol-definitions/{id}`**


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `200 OK` — `ProtocolDefinitionDto`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |

---

### 1.4 Get Protocol Definitions by URL

**`GET /v1/protocol-definitions/by-url?url={url}`**


**Query Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `url` | String | Yes | Protocol definition canonical URL |

**Response:** `200 OK` — `List<ProtocolDefinitionDto>` (all versions)

---

### 1.5 Get Protocol Definition by URL and Version

**`GET /v1/protocol-definitions/by-url-version?url={url}&version={version}`**


**Query Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `url` | String | Yes | Protocol definition canonical URL |
| `version` | String | Yes | Semantic version |

**Response:** `200 OK` — `ProtocolDefinitionDto`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | URL + version combination does not exist |

---

### 1.6 Retire Protocol Definition

**`POST /v1/protocol-definitions/{id}/retire`** — Retire a protocol definition and remove its trigger index.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `200 OK` — Updated `ProtocolDefinitionDto` with `status: "retired"`

**Side Effects:**
- Sets protocol definition status to `RETIRED`
- Deletes all `trigger_index` entries for this protocol definition
- Writes an audit log entry

---

### 1.7 Rebuild Trigger Index

**`POST /v1/protocol-definitions/{id}/rebuild-index`** — Rebuild the trigger index from the stored definition.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `200 OK`

**Use Case:** When the index parsing logic is updated, this endpoint allows re-indexing without reloading the protocol definition.

---

### 1.8 Delete Protocol Definition

**`DELETE /v1/protocol-definitions/{id}`** — Permanently delete a protocol definition.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `204 No Content`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | Protocol definition with the given ID does not exist |
| `409 Conflict` | Protocol instances still reference this protocol definition |

**Side Effects:**
- Deletes all `trigger_index` entries for this protocol definition
- Removes the `protocol_definition` row permanently

**Note:** A protocol definition cannot be deleted while protocol instances reference it. Retire the definition first and ensure all protocol instances are completed or cancelled before deleting.

---

## 2. Protocol Instances

Manage patient protocol enrollment lifecycle.

### 2.1 Get Protocol Instance

**`GET /v1/protocol-instances/{id}`** — Get a protocol instance with its steps and deviations.


**Response:** `200 OK`

```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "patientId": "260225-0002-5501",
  "protocolCanonical": "http://example.org/PlanDefinition/hiv-treatment|1.0",
  "protocolDefinitionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "active",
  "enrolledAt": "2026-03-15T10:30:00Z",
  "createdAt": "2026-03-15T10:30:00Z",
  "updatedAt": "2026-03-15T10:30:00Z",
  "steps": [
    {
      "id": "770e8400-e29b-41d4-a716-446655440002",
      "protocolInstanceId": "660e8400-e29b-41d4-a716-446655440001",
      "actionId": "viral-load-check",
      "repeatIndex": 0,
      "state": "completed",
      "dueDate": "2026-03-20T00:00:00Z",
      "overdueDate": "2026-03-25T00:00:00Z",
      "missedDate": "2026-04-01T00:00:00Z",
      "completedAt": "2026-03-18T14:30:00Z",
      "completedBySource": "ebuzima",
      "completionStatus": "early",
      "matchedEventId": "880e8400-e29b-41d4-a716-446655440003",
      "createdAt": "2026-03-15T10:30:00Z",
      "updatedAt": "2026-03-18T14:30:00Z"
    }
  ],
  "deviations": []
}
```

---

### 2.2 Withdraw Protocol Instance

**`POST /v1/protocol-instances/{id}/withdraw`** — Withdraw a patient from a protocol.

**Response:** `200 OK` — Updated `ProtocolInstanceDto`

**Pre-conditions:** Protocol must be in `ACTIVE` status.

---

## 3. Patient Protocol Tracking

Query patient compliance data.

### 3.1 List Patient Protocols

**`GET /v1/patients/{patientId}/protocol-instances`** — List all protocols for a patient.


**Response:** `200 OK` — `List<ProtocolInstanceDto>` (without steps/deviations)

---

### 3.2 List Active Patient Protocols

**`GET /v1/patients/{patientId}/protocol-instances/active`** — List only active protocols.


**Response:** `200 OK` — `List<ProtocolInstanceDto>`

---

### 3.3 Get Patient Protocol Detail

**`GET /v1/patients/{patientId}/protocol-instances/{protocolInstanceId}`** — Get detailed protocol view with steps and deviations.


**Response:** `200 OK` — `ProtocolInstanceDto` (includes `steps[]` and `deviations[]`)

---

### 3.4 List Protocol Steps

**`GET /v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/steps`**


**Response:** `200 OK` — `List<StepInstanceDto>`

---

### 3.5 List Protocol Deviations

**`GET /v1/patients/{patientId}/protocol-instances/{protocolInstanceId}/deviations`**


**Response:** `200 OK` — `List<DeviationDto>`

Returns compliance deviations detected by the scheduler for this protocol instance:
- **`OVERDUE`** — step was not completed by its `overdueDate` (scheduler transition `DUE → OVERDUE`)
- **`MISSED`** — step with `requiredBehavior=must` was not completed by its `missedDate` (scheduler transition `OVERDUE → MISSED`)

> **Note:** Steps with `requiredBehavior=could` transition to `SKIPPED` instead of `MISSED` and do **not** produce a deviation.

---

### 3.6 List Patient Events

**`GET /v1/patients/{patientId}/events?page={page}&size={size}`** — Paginated event log for a patient.


**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | 0 | Page number (0-based) |
| `size` | int | 20 | Page size |

**Response:** `200 OK` — `Page<EventLogDto>` (Spring Data paginated response)

---

## 4. Action Definitions

Manage Action Definitions that specify what the Intelligence Service does when an intelligence rule fires.

### 4.1 Register Action Definition

**`POST /v1/action-definitions`** — Register a new action definition.


**Request Body:**

```json
{
  "actionType": "send-notification",
  "name": "ANC Visit Overdue Alert",
  "description": "Alert when ANC visit exceeds tolerance window",
  "messageTemplate": "Patient {{patientId}} has missed {{actionId}}. Last due: {{dueDate}}. Days overdue: {{daysOverdue}}.",
  "severity": "medium",
  "target": "assigned_worker",
  "routing": {
    "adaptorId": "smartcare-chw-adaptor",
    "deliveryMode": "webhook",
    "endpoint": "https://adaptor.example.org/notifications"
  },
  "definitionCanonical": "ActivityDefinition/send-anc-overdue-alert"
}
```

**Response:** `201 Created`

```json
{
  "id": "ad-550e8400-e29b-41d4-a716-446655440010",
  "actionType": "send-notification",
  "name": "ANC Visit Overdue Alert",
  "description": "Alert when ANC visit exceeds tolerance window",
  "messageTemplate": "Patient {{patientId}} has missed {{actionId}}. Last due: {{dueDate}}. Days overdue: {{daysOverdue}}.",
  "severity": "medium",
  "target": "assigned_worker",
  "routing": {
    "adaptorId": "smartcare-chw-adaptor",
    "deliveryMode": "webhook",
    "endpoint": "https://adaptor.example.org/notifications"
  },
  "definitionCanonical": "ActivityDefinition/send-anc-overdue-alert",
  "createdAt": "2026-03-15T10:30:00Z",
  "updatedAt": "2026-03-15T10:30:00Z"
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `400 Bad Request` | Missing required fields or invalid action type |
| `409 Conflict` | An action definition with the same `definitionCanonical` already exists |

---

### 4.2 List Action Definitions

**`GET /v1/action-definitions`** — List all registered action definitions.


**Response:** `200 OK` — `List<ActionDefinitionDto>`

---

### 4.3 Get Action Definition by ID

**`GET /v1/action-definitions/{id}`**


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Action definition ID |

**Response:** `200 OK` — `ActionDefinitionDto`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |

---

### 4.4 Update Action Definition

**`PUT /v1/action-definitions/{id}`** — Update an existing action definition.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Action definition ID |

**Request Body:** Same structure as POST (all fields replace the current values).

**Response:** `200 OK` — Updated `ActionDefinitionDto`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `400 Bad Request` | Missing required fields or invalid action type |

---

## 5. Action Runs

Track execution of intelligence-triggered actions.

### 5.1 List Action Runs

**`GET /v1/action-runs?page={page}&size={size}`** — List action runs with pagination.


**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | int | 0 | Page number (0-based) |
| `size` | int | 20 | Page size |

**Response:** `200 OK` — `Page<ActionRunDto>`

---

### 5.2 Get Action Run by ID

**`GET /v1/action-runs/{id}`**


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Action run ID |

**Response:** `200 OK`

```json
{
  "id": "ar-660e8400-e29b-41d4-a716-446655440020",
  "actionDefinitionId": "ad-550e8400-e29b-41d4-a716-446655440010",
  "intelligenceEventId": "itrig-550e8400-e29b-41d4-a716-446655440099",
  "status": "completed",
  "patientId": "260225-0002-5501",
  "protocolInstanceId": "660e8400-e29b-41d4-a716-446655440001",
  "stepInstanceId": "770e8400-e29b-41d4-a716-446655440002",
  "actionType": "send-notification",
  "severity": "medium",
  "target": "assigned_worker",
  "resolvedMessage": "Patient 260225-0002-5501 has missed viral-load-check. Last due: 2026-03-20T00:00:00Z. Days overdue: 5.",
  "createdAt": "2026-03-25T00:00:05Z",
  "completedAt": "2026-03-25T00:00:06Z"
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |

---

### 5.3 Get Action Run Status

**`GET /v1/action-runs/{id}/status`** — Get current execution status of an action run.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Action run ID |

**Response:** `200 OK`

```json
{
  "id": "ar-660e8400-e29b-41d4-a716-446655440020",
  "status": "completed"
}
```

---

### 5.4 Cancel Action Run

**`POST /v1/action-runs/{id}:cancel`** — Cancel an in-progress action run.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Action run ID |

**Response:** `200 OK` — Updated `ActionRunDto` with `status: "cancelled"`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `409 Conflict` | Action run is already in a terminal state (`completed`, `failed`, `cancelled`) |

---

## 6. Intelligence Summary

### 6.1 Get Intelligence Summary

**`GET /v1/intelligence/summary?from={from}&to={to}`** — Intelligence events summary with counts by type and period.


**Query Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from` | ISO 8601 datetime | No | Start of period (defaults to 30 days ago) |
| `to` | ISO 8601 datetime | No | End of period (defaults to now) |

**Response:** `200 OK`

```json
{
  "period": {
    "from": "2026-03-01T00:00:00Z",
    "to": "2026-03-31T23:59:59Z"
  },
  "totalEvents": 42,
  "byType": {
    "overdue": 28,
    "missed": 8,
    "late_completion": 6
  },
  "bySeverity": {
    "low": 10,
    "medium": 18,
    "high": 12,
    "critical": 2
  },
  "byTarget": {
    "assigned_worker": 25,
    "supervisor": 12,
    "facility": 5
  }
}
```

---

## 7. Actuator Endpoints

Health and monitoring endpoints (no authentication required).

| Endpoint | Method | Description |
|---|---|---|
| `/actuator/health` | GET | Overall health status |
| `/actuator/health/liveness` | GET | Kubernetes liveness probe |
| `/actuator/health/readiness` | GET | Kubernetes readiness probe |
| `/actuator/info` | GET | Application metadata |
| `/actuator/prometheus` | GET | Prometheus metrics |
| `/actuator/metrics` | GET | Micrometer metrics listing |
| `/actuator/metrics/{name}` | GET | Individual metric detail |

---

## 8. Error Response Format

All errors follow a consistent structure:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Protocol definition with this url and version already exists",
  "path": "/v1/protocol-definitions",
  "timestamp": "2026-03-15T10:30:00Z",
  "fieldErrors": null
}
```

### Validation Error (400 with field details)

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/v1/protocol-definitions",
  "timestamp": "2026-03-15T10:30:00Z",
  "fieldErrors": [
    {
      "field": "planDefinitionJson",
      "message": "must not be blank"
    }
  ]
}
```

### Error Code Mapping

| HTTP Status | Exception Type | Meaning |
|---|---|---|
| `400` | `IllegalArgumentException` | Invalid input or business rule violation |
| `400` | `MethodArgumentNotValidException` | Bean validation failure |
| `404` | `NoSuchElementException` | Resource not found |
| `409` | `IllegalStateException` | State conflict (e.g., completing a non-active protocol) |
| `422` | `FhirValidationException` | FHIR resource validation failure |
| `422` | `ExpressionEvaluationException` | JSONLogic expression evaluation failure |
| `500` | `Exception` | Unexpected server error |

---

## 9. DTO Schemas

### ProtocolDefinitionDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `url` | String | No | FHIR canonical URL |
| `version` | String | No | Semantic version |
| `canonical` | String | No | `url\|version` |
| `status` | String | No | `active` or `retired` |
| `loadedAt` | OffsetDateTime | No | When the definition was loaded |
| `definition` | Map | No | Full FHIR PlanDefinition as JSONB |

### ProtocolInstanceDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `patientId` | String | No | Patient identifier |
| `protocolCanonical` | String | No | `url\|version` of the protocol definition |
| `protocolDefinitionId` | UUID | No | FK to ProtocolDefinition |
| `status` | String | No | `active`, `completed`, `withdrawn`, `expired` |
| `enrolledAt` | OffsetDateTime | No | Enrollment timestamp |
| `createdAt` | OffsetDateTime | No | Record creation |
| `updatedAt` | OffsetDateTime | No | Last update |
| `steps` | List | Yes | Step instances (null in list views) |
| `deviations` | List | Yes | Deviations (null in list views) |

### StepInstanceDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `protocolInstanceId` | UUID | No | FK to ProtocolInstance |
| `actionId` | String | No | Protocol definition action ID |
| `repeatIndex` | int | No | 0-based repeat counter |
| `state` | String | No | `pending`, `due`, `overdue`, `missed`, `completed`, `skipped` |
| `dueDate` | OffsetDateTime | Yes | When step becomes due |
| `overdueDate` | OffsetDateTime | Yes | When step becomes overdue |
| `missedDate` | OffsetDateTime | Yes | When step is considered missed |
| `completedAt` | OffsetDateTime | Yes | Completion timestamp |
| `completedBySource` | String | Yes | Source system that completed the step |
| `completionStatus` | String | Yes | `on_time`, `early`, `late` |
| `matchedEventId` | UUID | Yes | EventLog ID that triggered completion |
| `requiredBehavior` | String | Yes | FHIR `requiredBehavior` code: `must`, `could`, `must-unless-documented` |
| `createdAt` | OffsetDateTime | No | Record creation |
| `updatedAt` | OffsetDateTime | No | Last update |

### DeviationDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `protocolInstanceId` | UUID | No | FK to ProtocolInstance |
| `stepInstanceId` | UUID | Yes | FK to StepInstance (null for protocol-level deviations) |
| `deviationType` | String | No | `overdue`, `missed` |
| `detectedAt` | OffsetDateTime | No | Detection timestamp |
| `intelligenceEventId` | UUID | Yes | ID of published intelligence event |
| `metadata` | Map | Yes | Additional context (JSONB) |

### EventLogDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `cloudeventsId` | String | No | CloudEvents ID |
| `source` | String | No | Event source URI |
| `sourceEventId` | String | Yes | Original event ID from source |
| `subject` | String | Yes | Event subject (typically patientId) |
| `type` | String | No | Event type |
| `correlationId` | String | Yes | Trace correlation ID |
| `eventTime` | OffsetDateTime | Yes | Original event timestamp |
| `receivedAt` | OffsetDateTime | No | When the service received the event |
| `data` | Map | Yes | Event payload (JSONB) |
| `actionId` | String | Yes | Matched action ID |
| `facilityId` | String | Yes | Facility identifier |
| `processingStatus` | String | No | `matched`, `zero_match`, `duplicate` |
| `protocolInstanceId` | UUID | Yes | Matched protocol instance |
| `protocolDefinitionId` | UUID | Yes | Matched protocol definition |
| `matchedStepInstanceId` | UUID | Yes | Matched step instance |

### ActionDefinitionDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `actionType` | String | No | Action type: `send-notification`, `create-task`, `forward-data`, `escalate` |
| `name` | String | No | Human-readable name |
| `description` | String | Yes | Description of the action |
| `messageTemplate` | String | Yes | Template with `{{variable}}` placeholders |
| `severity` | String | No | Default severity: `low`, `medium`, `high`, `critical` |
| `target` | String | No | Default target: `patient`, `assigned_worker`, `supervisor`, `facility` |
| `routing` | Map | Yes | Routing configuration (adaptor, delivery mode, endpoint) |
| `definitionCanonical` | String | No | FHIR `ActivityDefinition` canonical reference |
| `createdAt` | OffsetDateTime | No | Record creation |
| `updatedAt` | OffsetDateTime | No | Last update |

### ActionRunDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `actionDefinitionId` | UUID | No | FK to ActionDefinition |
| `intelligenceEventId` | UUID | No | ID of the intelligence trigger event |
| `status` | String | No | `pending`, `in_progress`, `completed`, `failed`, `cancelled` |
| `patientId` | String | No | Patient identifier |
| `protocolInstanceId` | UUID | No | Associated protocol instance |
| `stepInstanceId` | UUID | Yes | Associated step instance |
| `actionType` | String | No | Action type executed |
| `severity` | String | No | Effective severity |
| `target` | String | No | Effective target |
| `resolvedMessage` | String | Yes | Message with placeholders resolved |
| `createdAt` | OffsetDateTime | No | Record creation |
| `completedAt` | OffsetDateTime | Yes | Completion timestamp |
| `failureReason` | String | Yes | Reason for failure (if status is `failed`) |
