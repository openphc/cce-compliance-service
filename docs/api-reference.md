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

**`POST /v1/compliance/protocol-definitions`** — Load a new clinical protocol definition.


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
  "status": "ACTIVE",
  "loadedAt": "2026-03-15T10:30:00Z",
  "definition": { ... }
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `400 Bad Request` | `planDefinitionJson` is blank, url+version already exists, or a business-rule validation fails (duplicate/unresolvable action IDs, unsupported action type, unsupported trigger) |
| `422 Unprocessable Entity` | `planDefinitionJson` does not parse into a valid FHIR PlanDefinition resource |

---

### 1.2 List Protocol Definitions

**`GET /v1/compliance/protocol-definitions`** — List all protocol definitions, active and retired (paginated). There is no status filter on this endpoint.

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `page` | Integer | No | `0` | Page number (zero-based) |
| `size` | Integer | No | `20` | Page size |
| `sort` | String | No | none (unsorted) | Sort field and direction, e.g. `loadedAt,desc` |

**Response:** `200 OK` — `Page<ProtocolDefinitionDto>`

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "url": "http://example.org/PlanDefinition/hiv-treatment",
      "version": "1.0",
      "canonical": "http://example.org/PlanDefinition/hiv-treatment|1.0",
      "status": "ACTIVE",
      "loadedAt": "2026-03-15T10:30:00Z",
      "definition": { ... }
    }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 20 },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "first": true
}
```

---

### 1.3 Get Protocol Definition by ID

**`GET /v1/compliance/protocol-definitions/{id}`**


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

**`GET /v1/compliance/protocol-definitions/by-url?url={url}`**


**Query Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `url` | String | Yes | Protocol definition canonical URL |

**Response:** `200 OK` — `List<ProtocolDefinitionDto>` (all versions)

---

### 1.5 Get Protocol Definition by URL and Version

**`GET /v1/compliance/protocol-definitions/by-url-version?url={url}&version={version}`**


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

**`POST /v1/compliance/protocol-definitions/{id}/retire`** — Retire a protocol definition and remove its trigger index.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `200 OK` — Updated `ProtocolDefinitionDto` with `status: "RETIRED"`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `409 Conflict` | Protocol definition is already retired |

**Side Effects:**
- Sets protocol definition status to `RETIRED`
- Deletes all `trigger_index` entries for this protocol definition
- Writes an audit log entry

---

### 1.7 Rebuild Trigger Index

**`POST /v1/compliance/protocol-definitions/{id}/rebuild-index`** — Rebuild the trigger index from the stored definition.


**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Protocol definition ID |

**Response:** `200 OK`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |

**Use Case:** When the index parsing logic is updated, this endpoint allows re-indexing without reloading the protocol definition.

---

### 1.8 Delete Protocol Definition

**`DELETE /v1/compliance/protocol-definitions/{id}`** — Permanently delete a protocol definition.


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

## 2. Actuator Endpoints

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

## 3. Error Response Format

All errors follow a consistent structure (`ErrorResponse`). `correlationId` is populated from the request's MDC correlation ID when present and omitted otherwise:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Protocol definition already exists for url=..., version=...",
  "timestamp": "2026-03-15T10:30:00Z",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### Validation Error (400 from bean validation)

Bean validation failures (e.g. a blank required field) do not return a structured field-error list. Instead, all failing fields are concatenated into a single `message` string as `field: defaultMessage`, semicolon-separated:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "planDefinitionJson: planDefinitionJson must not be blank",
  "timestamp": "2026-03-15T10:30:00Z"
}
```

### Error Code Mapping

| HTTP Status | Exception Type | Meaning |
|---|---|---|
| `400` | `IllegalArgumentException` | Invalid input or business rule violation |
| `400` | `MethodArgumentNotValidException` | Bean validation failure (e.g. `@NotBlank`) |
| `404` | `jakarta.persistence.EntityNotFoundException` | Resource not found |
| `409` | `IllegalStateException` | State conflict (e.g., retiring an already-retired protocol, deleting a definition with existing instances) |
| `422` | `ca.uhn.fhir.parser.DataFormatException` | Submitted JSON does not parse into a valid FHIR resource |
| `422` | `UnsupportedExpressionLanguageException` | Condition/trigger expression uses an unsupported expression language (only `text/jsonlogic` is supported) |
| `500` | `Exception` | Unexpected server error |

---

## 4. DTO Schemas

> **Enum values:** All status, state, and type fields use enum values described in [Data Dictionary §15 — Enumerated Value Reference](data-dictionary.md#15-enumerated-value-reference).

### ProtocolDefinitionDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `url` | String | No | FHIR canonical URL |
| `version` | String | No | Semantic version |
| `canonical` | String | No | `url\|version` |
| `status` | String | No | `ACTIVE` or `RETIRED` |
| `loadedAt` | OffsetDateTime | No | When the definition was loaded |
| `definition` | Map | No | Full FHIR PlanDefinition as JSONB |

---

## 5. Action Definitions

Manage FHIR R4 `ActivityDefinition` resources as intelligence action definitions.

### 5.1 Create Action Definition

**`POST /v1/compliance/action-definitions`** — Register a new action definition.

**Request Body:**

```json
{
  "definitionJson": "{\"resourceType\":\"ActivityDefinition\",\"url\":\"ActivityDefinition/anc-escalation-notification\",\"version\":\"1.0\",\"name\":\"anc-escalation-notification\",\"title\":\"ANC Escalation Notification\",\"status\":\"active\",\"kind\":\"CommunicationRequest\"}"
}
```

**Response:** `201 Created`

```json
{
  "id": "aa0e8400-e29b-41d4-a716-446655440010",
  "canonicalUrl": "ActivityDefinition/anc-escalation-notification",
  "version": "1.0",
  "canonical": "ActivityDefinition/anc-escalation-notification|1.0",
  "name": "anc-escalation-notification",
  "title": "ANC Escalation Notification",
  "status": "ACTIVE",
  "actionType": "CommunicationRequest",
  "definition": { ... },
  "createdAt": "2026-04-07T10:30:00Z",
  "updatedAt": "2026-04-07T10:30:00Z"
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `400 Bad Request` | `definitionJson` is blank or not valid JSON, a required field (`url`, `version`, `kind`) is missing/empty, `kind` is not a supported action type, or url+version already exists |

**Note:** Unlike protocol definitions, `definitionJson` is parsed with a plain JSON parser (not the FHIR resource parser), so there is no `422` response for this endpoint — malformed or invalid input results in `400`.

---

### 5.2 List Action Definitions

**`GET /v1/compliance/action-definitions`** — List all action definitions (paginated).

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `status` | String | No | — | Filter by status (`ACTIVE`, `RETIRED`) |
| `page` | Integer | No | `0` | Page number (zero-based) |
| `size` | Integer | No | `20` | Page size |
| `sort` | String | No | none (unsorted) | Sort field and direction, e.g. `createdAt,desc` |

**Response:** `200 OK` — `Page<ActionDefinitionDto>`

**Error Responses:**

| Status | Condition |
|---|---|
| `400 Bad Request` | `status` is not one of `ACTIVE`, `RETIRED` |

---

### 5.3 Get Action Definition by ID

**`GET /v1/compliance/action-definitions/{id}`**

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

### 5.4 Update Action Definition

**`PUT /v1/compliance/action-definitions/{id}`** — Update an existing action definition.

**Request Body:**

```json
{
  "definitionJson": "{\"resourceType\":\"ActivityDefinition\", ...}"
}
```

**Response:** `200 OK` — Updated `ActionDefinitionDto`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `400 Bad Request` | `definitionJson` is blank or not valid JSON, a required field (`url`, `version`, `kind`) is missing/empty, `kind` is not a supported action type, or the changed url+version already exists on another action definition |

---

### 5.5 Retire Action Definition

**`POST /v1/compliance/action-definitions/{id}/retire`** — Retire an action definition.

**Response:** `200 OK` — Updated `ActionDefinitionDto` with `status: "RETIRED"`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `409 Conflict` | Action definition is already retired |

---

### 5.6 Delete Action Definition

**`DELETE /v1/compliance/action-definitions/{id}`** — Permanently delete an action definition.

**Response:** `204 No Content`

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |
| `409 Conflict` | Intelligence events reference this action definition |

---

## 6. Intelligence Events

View intelligence action execution records.

### 6.1 List Intelligence Events

**`GET /v1/compliance/intelligence-events`** — List intelligence event logs with optional filters (paginated).

**Note:** Filters are mutually exclusive, not combined. Only one is applied, in this priority order: `protocolInstanceId`, then `actionDefinitionId`, then `published`. If none is supplied, all events are returned.

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `protocolInstanceId` | UUID | No | — | Filter by protocol instance (takes priority over the other filters) |
| `actionDefinitionId` | UUID | No | — | Filter by action definition (used only if `protocolInstanceId` is absent) |
| `published` | Boolean | No | — | Filter by publish status (`true` or `false`); used only if `protocolInstanceId` and `actionDefinitionId` are both absent |
| `page` | Integer | No | `0` | Page number (zero-based) |
| `size` | Integer | No | `20` | Page size |
| `sort` | String | No | none (unsorted) | Sort field and direction, e.g. `createdAt,desc` |

**Response:** `200 OK` — `Page<IntelligenceEventLogDto>`

---

### 6.2 Get Intelligence Event by ID

**`GET /v1/compliance/intelligence-events/{id}`**

**Path Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `id` | UUID | Intelligence event log ID |

**Response:** `200 OK`

```json
{
  "id": "bb0e8400-e29b-41d4-a716-446655440020",
  "eventPayload": {
    "id": "550e8400-e29b-41d4-a716-446655440099",
    "subject": "260225-0002-5501",
    "intelligenceEventId": "bb0e8400-e29b-41d4-a716-446655440020",
    "actionDefinitionId": "aa0e8400-e29b-41d4-a716-446655440010",
    "protocolDefinitionId": "ppd00001-0001-0001-0001-000000000001",
    "actionType": "CommunicationRequest",
    "severity": "HIGH",
    "intelligenceDestination": "openMRS",
    "stepState": "overdue",
    "actionId": "anc-visit-2",
    "protocolCanonical": "http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1",
    "detectedAt": "2026-04-07T10:30:05Z"
  },
  "actionDefinitionId": "aa0e8400-e29b-41d4-a716-446655440010",
  "protocolInstanceId": "660e8400-e29b-41d4-a716-446655440001",
  "stepInstanceId": "770e8400-e29b-41d4-a716-446655440002",
  "deviationId": "dd0e8400-e29b-41d4-a716-446655440003",
  "subject": "260225-0002-5501",
  "actionType": "CommunicationRequest",
  "intelligenceDestination": "openMRS",
  "stepState": "overdue",
  "triggerReason": "overdue",
  "stepActionId": "anc-visit-2-overdue-escalation",
  "evaluationExpression": "{\"and\": [{\"==\": [{\"var\": \"stepState\"}, \"overdue\"]}, {\">\": [{\"var\": \"daysOverdue\"}, 3]}]}",
  "evaluationContext": {
    "stepState": "overdue",
    "deviationType": "overdue",
    "actionId": "anc-visit-2",
    "daysOverdue": 5
  },
  "published": true,
  "publishedAt": "2026-04-07T10:30:05Z",
  "createdAt": "2026-04-07T10:30:05Z"
}
```

**Error Responses:**

| Status | Condition |
|---|---|
| `404 Not Found` | ID does not exist |

---

## 7. DTO Schemas — Intelligence

### ActionDefinitionDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier |
| `canonicalUrl` | String | No | FHIR canonical URL |
| `version` | String | No | Semantic version |
| `canonical` | String | No | `canonicalUrl\|version` |
| `name` | String | Yes | Computer-friendly name |
| `title` | String | Yes | Human-readable title |
| `status` | String | No | `ACTIVE` or `RETIRED` |
| `actionType` | String | No | `CommunicationRequest`, `Task`, `ServiceRequest` |
| `definition` | Map | No | Full ActivityDefinition as JSONB |
| `createdAt` | OffsetDateTime | No | Record creation |
| `updatedAt` | OffsetDateTime | No | Last update |

### IntelligenceEventLogDto

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID | No | Unique identifier (maps to `intelligenceEventId` in Kafka event) |
| `eventPayload` | JsonNode | No | Complete `IntelligenceTriggerEvent` published to Kafka |
| `actionDefinitionId` | UUID | No | ActionDefinition that was resolved |
| `protocolInstanceId` | UUID | No | Patient's protocol instance |
| `stepInstanceId` | UUID | Yes | Step that triggered the action |
| `deviationId` | UUID | Yes | Deviation that triggered the action. `NULL` for completion-triggered |
| `subject` | String | No | Patient UPID |
| `actionType` | String | No | `CommunicationRequest`, `Task`, `ServiceRequest` |
| `intelligenceDestination` | String | Yes | Intelligence destination |
| `stepState` | String | Yes | Step state at evaluation time |
| `triggerReason` | String | No | `overdue`, `missed`, `completion` |
| `stepActionId` | String | Yes | PlanDefinition intelligence action ID that fired |
| `evaluationExpression` | String | Yes | Condition expression evaluated (audit/debug) |
| `evaluationContext` | JsonNode | Yes | Runtime variables passed to evaluator (JSONB) |
| `published` | boolean | No | Whether event was successfully published to Kafka |
| `publishedAt` | OffsetDateTime | Yes | Kafka publish timestamp |
| `createdAt` | OffsetDateTime | No | Record creation |
