# Data Dictionary

> **CCE Compliance Service** — Complete database schema reference  
> **Database**: PostgreSQL 16 | **Schema**: `public` | **Migration**: Flyway  
> **Last Updated**: 2026-03-09

---

## Table of Contents

1. [Entity Relationship Diagram](#1-entity-relationship-diagram)
2. [Table Summary](#2-table-summary)
3. [protocol_definition](#3-protocol_definition)
4. [protocol_instance](#4-protocol_instance)
5. [step_instance](#5-step_instance)
6. [deviation](#6-deviation)
7. [trigger_index](#7-trigger_index)
8. [event_log](#8-event_log)
9. [audit_log](#9-audit_log)
10. [action_definition](#10-action_definition)
11. [action_run](#11-action_run)
12. [Enumerated Value Reference](#12-enumerated-value-reference)
13. [Relationships & Foreign Keys](#13-relationships--foreign-keys)
14. [JSONB Column Schemas](#14-jsonb-column-schemas)

---

## 1. Entity Relationship Diagram

```mermaid
erDiagram
    PROTOCOL_DEFINITION ||--o{ PROTOCOL_INSTANCE : "defines"
    PROTOCOL_DEFINITION ||--o{ TRIGGER_INDEX : "indexed by"
    PROTOCOL_INSTANCE ||--o{ STEP_INSTANCE : "contains"
    PROTOCOL_INSTANCE ||--o{ DEVIATION : "has"
    STEP_INSTANCE ||--o{ DEVIATION : "causes"
    DEVIATION ||--o| ACTION_RUN : "triggers"
    ACTION_DEFINITION ||--o{ ACTION_RUN : "executed as"

    PROTOCOL_DEFINITION {
        uuid id PK
        varchar url
        varchar version
        varchar status
        jsonb definition
        timestamptz loaded_at
    }

    PROTOCOL_INSTANCE {
        uuid id PK
        varchar patient_id
        varchar protocol_canonical
        uuid protocol_definition_id FK
        timestamptz enrolled_at
        varchar status
        timestamptz created_at
        timestamptz updated_at
    }

    STEP_INSTANCE {
        uuid id PK
        uuid protocol_instance_id FK
        varchar action_id
        int repeat_index
        varchar state
        timestamptz due_date
        timestamptz overdue_date
        timestamptz missed_date
        timestamptz completed_at
        varchar completed_by_source
        varchar completion_status
        uuid matched_event_id
        varchar required_behavior
        timestamptz created_at
        timestamptz updated_at
    }

    DEVIATION {
        uuid id PK
        uuid protocol_instance_id FK
        uuid step_instance_id FK
        varchar deviation_type
        timestamptz detected_at
        uuid intelligence_event_id
        jsonb metadata
    }

    TRIGGER_INDEX {
        varchar resource_type PK
        varchar path PK
        varchar code_system PK
        varchar code_value PK
        uuid protocol_definition_id PK
        varchar action_id PK
    }

    EVENT_LOG {
        uuid id PK
        varchar cloudeventsid
        varchar source
        varchar source_event_id
        varchar subject
        varchar type
        timestamptz event_time
        timestamptz received_at
        varchar correlation_id
        jsonb data
        uuid protocol_instance_id
        uuid protocol_definition_id
        varchar action_id
        varchar facility_id
        varchar processing_status
        uuid matched_step_instance_id
    }

    AUDIT_LOG {
        uuid id PK
        varchar event_category
        varchar event_type
        varchar actor
        varchar resource_type
        varchar resource_id
        jsonb details
        varchar ip_address
        timestamptz timestamp
    }

    ACTION_DEFINITION {
        uuid id PK
        varchar action_type
        varchar name
        varchar description
        varchar message_template
        varchar severity
        varchar target
        jsonb routing
        varchar definition_canonical
        timestamptz created_at
        timestamptz updated_at
    }

    ACTION_RUN {
        uuid id PK
        uuid action_definition_id FK
        uuid intelligence_event_id
        varchar status
        varchar patient_id
        uuid protocol_instance_id FK
        uuid step_instance_id FK
        varchar action_type
        varchar severity
        varchar target
        text resolved_message
        varchar failure_reason
        timestamptz created_at
        timestamptz completed_at
    }
```

---

## 2. Table Summary

| # | Table | Purpose | Row Growth |
|---|-------|---------|-----------|
| 1 | `protocol_definition` | Stores FHIR R4 PlanDefinition resources (protocol templates) | Low (tens) |
| 2 | `protocol_instance` | Patient enrollments in specific protocols | Medium (per-patient) |
| 3 | `step_instance` | Individual action steps within a patient's protocol journey | Medium–High |
| 4 | `deviation` | Compliance deviations (overdue, missed) | Medium |
| 5 | `trigger_index` | Inverted index for fast Tier 1 structural event matching | Low (rebuilt on protocol load) |
| 6 | `event_log` | Immutable log of all inbound CloudEvents and their processing outcomes | High (every event) |
| 7 | `audit_log` | System and user audit trail | Medium–High || 9 | `action_definition` | Intelligence action definitions (`ActivityDefinition` resources) | Low (tens) |
| 10 | `action_run` | Intelligence action execution records | Medium–High |
---

## 3. protocol_definition

Stores FHIR R4 **PlanDefinition** resources that define compliance protocols. Each row represents a versioned protocol template containing actions, triggers, conditions, timing constraints, and related action dependencies. The full PlanDefinition JSON is stored in a JSONB column to preserve the complete FHIR resource while allowing PostgreSQL JSON queries.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. Auto-generated unique identifier. |
| `url` | `VARCHAR` | **NOT NULL** | — | FHIR canonical URL (e.g., `http://openphc.org/fhir/PlanDefinition/anc-high-risk`). Combined with `version` forms the canonical reference. |
| `version` | `VARCHAR` | **NOT NULL** | — | Semantic version (e.g., `2.1`). Allows multiple versions of the same protocol URL to coexist. |
| `status` | `VARCHAR` | **NOT NULL** | — | Lifecycle status. Only `ACTIVE` definitions participate in trigger matching. See [ProtocolDefinitionStatus](#protocoldefinitionstatus). |
| `definition` | `JSONB` | **NOT NULL** | — | Full FHIR R4 PlanDefinition resource. Contains `action[]` with triggers, conditions, timing, and related actions. See [JSONB: definition](#protocol_definition--definition). |
| `loaded_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | When this protocol definition was loaded into the system. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `protocol_definition_pkey` | `id` |
| Unique | `protocol_definition_url_version_key` | `(url, version)` — Prevents duplicate protocol versions. |
| Check | — | `status IN ('ACTIVE', 'RETIRED')` |
| GIN Index | `idx_protocol_definition_triggers` | `definition` (`jsonb_path_ops`) — Fast JSON path queries. |

### Canonical Reference

The **canonical reference** is `url|version` (e.g., `http://openphc.org/fhir/PlanDefinition/anc-high-risk|2.1`). Computed by the JPA entity method `getCanonical()` and stored in `protocol_instance.protocol_canonical` for denormalized lookups.

---

## 4. protocol_instance

Represents a **patient's enrollment** in a specific compliance protocol. Created when the Compliance Engine processes an inbound event that matches a protocol's enrollment trigger. Each patient can have at most one `ACTIVE` instance per protocol definition.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `patient_id` | `VARCHAR` | **NOT NULL** | — | UPID of the enrolled patient (e.g., `260115-0001-7823`). Derived from the CloudEvent `subject` field. |
| `protocol_canonical` | `VARCHAR` | **NOT NULL** | — | Denormalized `url|version` reference. Stored for fast display without joining `protocol_definition`. |
| `protocol_definition_id` | `UUID` | **NOT NULL** | — | Foreign key → `protocol_definition.id`. |
| `enrolled_at` | `TIMESTAMPTZ` | **NOT NULL** | — | Enrollment timestamp. Used as the anchor for timing calculations. |
| `status` | `VARCHAR` | **NOT NULL** | — | Instance lifecycle status. See [ProtocolInstanceStatus](#protocolinstancestatus). |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Record creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Last modification timestamp. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `protocol_instance_pkey` | `id` |
| Foreign Key | `protocol_instance_protocol_definition_id_fkey` | `protocol_definition_id` → `protocol_definition(id)` |
| Check | — | `status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN', 'EXPIRED')` |
| B-tree Index | `idx_protocol_instance_patient` | `patient_id` — Fast lookup of all protocol enrollments for a patient. |
| Partial B-tree | `idx_protocol_instance_status` | `status WHERE status = 'ACTIVE'` — Optimizes active enrollment queries. |

---

## 5. step_instance

Tracks an **individual action occurrence** within a patient's protocol journey. Each step corresponds to a single `action` from the protocol definition. Steps follow a state machine lifecycle: `PENDING → DUE → OVERDUE → MISSED` (scheduler-driven, for `must` steps) or `→ SKIPPED` (scheduler-driven, for `could` steps) or `→ COMPLETED` (event-driven). Repeating steps are differentiated by `repeat_index`.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `protocol_instance_id` | `UUID` | **NOT NULL** | — | Foreign key → `protocol_instance.id`. |
| `action_id` | `VARCHAR` | **NOT NULL** | — | Protocol definition `action.id` this step instantiates (e.g., `anc-visit-1`). |
| `repeat_index` | `INTEGER` | **NOT NULL** | `0` | Zero-based occurrence counter for repeating actions. Non-repeating actions always have index 0. |
| `state` | `VARCHAR` | **NOT NULL** | — | Current step state. See [StepState](#stepstate). |
| `due_date` | `TIMESTAMPTZ` | Yes | — | Scheduled due date. Calculated from `relatedAction.offsetDuration`. `NULL` for event-triggered steps. |
| `overdue_date` | `TIMESTAMPTZ` | Yes | — | Overdue threshold. Typically `due_date + tolerance_days`. |
| `missed_date` | `TIMESTAMPTZ` | Yes | — | Missed cutoff date. |
| `completed_at` | `TIMESTAMPTZ` | Yes | — | Completion timestamp. `NULL` for non-completed steps. |
| `completed_by_source` | `VARCHAR` | Yes | — | CloudEvent `source` that completed this step. |
| `completion_status` | `VARCHAR` | Yes | — | Timeliness classification. See [CompletionStatus](#completionstatus). |
| `matched_event_id` | `UUID` | Yes | — | Links to `event_log.id` that completed this step. |
| `required_behavior` | `VARCHAR` | Yes | — | FHIR `requiredBehavior` code from `PlanDefinition.action`: `must`, `could`, or `must-unless-documented`. Determines whether the step produces a deviation on non-completion. |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Record creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Last modification timestamp. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `step_instance_pkey` | `id` |
| Foreign Key | `step_instance_protocol_instance_id_fkey` | `protocol_instance_id` → `protocol_instance(id)` |
| Check | — | `state IN ('PENDING', 'DUE', 'OVERDUE', 'MISSED', 'COMPLETED', 'SKIPPED')` |
| Check | — | `completion_status IN ('ON_TIME', 'EARLY', 'LATE')` |
| Check | — | `required_behavior IN ('must', 'could', 'must-unless-documented')` |
| B-tree Index | `idx_step_instance_protocol` | `protocol_instance_id` — All steps within a protocol instance. |
| Partial B-tree | `idx_step_instance_state` | `state WHERE state IN ('PENDING', 'DUE', 'OVERDUE')` — Active (non-terminal) steps. |
| Partial B-tree | `idx_step_instance_due_date` | `due_date WHERE state IN ('PENDING', 'DUE', 'OVERDUE')` — Scheduler time-based transitions. |

### State Machine

```
                   ┌──────────┐
         ┌─────────│ PENDING  │─────────┐
         │         └────┬─────┘         │
         │   scheduler  │               │ event match
         │   (due_date  │               │ (completes)
         │    reached)  ▼               ▼
         │         ┌──────────┐    ┌───────────┐
         │         │   DUE    │───▶│ COMPLETED │
         │         └────┬─────┘    └───────────┘
         │   tolerance  │               ▲
         │   window     │               │ event match
         │   expired    ▼               │
         │         ┌──────────┐         │
         │         │ OVERDUE  │─────────┘
         │         └────┬─────┘
         │   missed     │
         │   cutoff     ▼
         │         ┌──────────┐
         │         │  MISSED  │    (must)
         │         └──────────┘
         │   missed     │
         │   cutoff     │ (could)
         │   (could)    ▼
         │         ┌──────────┐
         │         │ SKIPPED  │
         │         └──────────┘
```

---

## 6. deviation

Records **compliance deviations** detected during protocol execution. Created when a step transitions to `OVERDUE` or `MISSED`. Intelligence trigger events are published to Kafka upon deviation detection, and intelligence rules on the step are evaluated to produce additional intelligence events.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `protocol_instance_id` | `UUID` | **NOT NULL** | — | Foreign key → `protocol_instance.id`. |
| `step_instance_id` | `UUID` | **NOT NULL** | — | Foreign key → `step_instance.id`. |
| `deviation_type` | `VARCHAR` | **NOT NULL** | — | Type classification. See [DeviationType](#deviationtype). |
| `detected_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Detection timestamp. |
| `intelligence_event_id` | `UUID` | Yes | — | Links to the intelligence trigger event published to Kafka when the deviation was detected. Populated after successful Kafka publish. `NULL` if the intelligence rule did not fire or publish failed. |
| `metadata` | `JSONB` | Yes | — | Deviation-type-specific timing details. See [JSONB: deviation metadata](#deviation--metadata). |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `deviation_pkey` | `id` |
| Foreign Key | `deviation_protocol_instance_id_fkey` | `protocol_instance_id` → `protocol_instance(id)` |
| Foreign Key | `deviation_step_instance_id_fkey` | `step_instance_id` → `step_instance(id)` |
| Check | — | `deviation_type IN ('OVERDUE', 'MISSED')` |
| B-tree Index | `idx_deviation_protocol` | `protocol_instance_id` |
| B-tree Index | `idx_deviation_type` | `deviation_type` |

---

## 7. trigger_index

An **inverted index** for fast **Tier 1 structural matching** of inbound CloudEvents to protocol definition actions. Built at protocol load time by decomposing each action's trigger `data[].codeFilter[]` entries into `(resourceType, path, codeSystem, codeValue)` rows. Rebuilt whenever a protocol is reloaded.

Only triggers that contain a `data[]` section produce `trigger_index` entries. **Condition-only triggers** (no `data[]`, only `condition`) are held in-memory and evaluated via Tier 2 for every inbound event.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `resource_type` | `VARCHAR` | **NOT NULL** | — | FHIR resource type from the trigger's `DataRequirement.type` (e.g., `Encounter`, `Observation`). |
| `path` | `VARCHAR` | **NOT NULL** | — | The `codeFilter.path` this row was decomposed from (e.g., `type`, `status`, `class`, `serviceType`). |
| `code_system` | `VARCHAR` | **NOT NULL** | `''` | Code system URI. Empty string = no system specified. |
| `code_value` | `VARCHAR` | **NOT NULL** | `''` | Code value. Empty string = resource-type-only match (no codeFilter). |
| `protocol_definition_id` | `UUID` | **NOT NULL** | — | Foreign key → `protocol_definition.id`. |
| `action_id` | `VARCHAR` | **NOT NULL** | — | Protocol definition `action.id` this trigger belongs to. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Composite PK | `trigger_index_pkey` | `(resource_type, path, code_system, code_value, protocol_definition_id, action_id)` |
| Foreign Key | `trigger_index_protocol_definition_id_fkey` | `protocol_definition_id` → `protocol_definition(id)` |
| B-tree Index | `idx_trigger_index_resource` | `resource_type` — Resource-type-only matching. |
| B-tree Index | `idx_trigger_index_code` | `(resource_type, path, code_system, code_value)` — Full structural matching (primary query path). |

### Matching Query

Uses `GROUP BY` + `HAVING` to enforce **AND semantics** — all codeFilter paths for an action must match:

```sql
SELECT protocol_definition_id, action_id
FROM trigger_index
WHERE resource_type = :resourceType
  AND CONCAT(path, '|', code_system, '|', code_value) IN (:codeTriples)
GROUP BY protocol_definition_id, action_id
HAVING COUNT(DISTINCT path) = (
    SELECT COUNT(DISTINCT t2.path)
    FROM trigger_index t2
    WHERE t2.protocol_definition_id = trigger_index.protocol_definition_id
      AND t2.action_id = trigger_index.action_id
      AND t2.resource_type = trigger_index.resource_type
);
```

The `:codeTriples` parameter is a list of `path|system|code` strings extracted from the inbound event payload. The correlated subquery counts the **total** distinct paths each action requires, so actions with different numbers of codeFilters are correctly evaluated in a single query.

### Load-Time Validation

| Trigger Shape | Index Entries | Matching Scenario |
|---|---|---|
| `data[].type` only (no `codeFilter[]`, no `condition`) | Resource-type-only row | Scenario 1 (F1) — matches every event of that type |
| `data[].type` + `codeFilter[]` (no `condition`) | Decomposed `(path, system, code)` rows | Scenario 2 (F1,F2) — Tier 1 only |
| `data[].type` + `condition` (no `codeFilter[]`) | Resource-type-only row | Scenario 3 (F1,F3) — type match → Tier 2 |
| `data[].type` + `codeFilter[]` + `condition` | Decomposed `(path, system, code)` rows | Scenario 4 (F1,F2,F3) — Tier 1 → Tier 2 |
| `condition` only (no `data[]`) | **None** — held in-memory | Scenario 5 (F3) — Tier 2 only |
| No `data[]` and no `condition` | **Rejected at load time** | N/A |

---

## 8. event_log

**Immutable append-only log** of every inbound CloudEvent. Records the full event payload and processing outcome. Used for:
- **Idempotency**: `(cloudevents_id, source)` uniqueness prevents duplicate processing.
- **Auditability**: Complete provenance trail of every clinical event.
- **Troubleshooting**: Full payload preservation enables replay and debugging.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `cloudevents_id` | `VARCHAR` | **NOT NULL** | — | CloudEvents `id`. Used with `source` for idempotency. |
| `source` | `VARCHAR` | **NOT NULL** | — | CloudEvents `source` (e.g., `rhie-mediator`, `smartcare-emr`). |
| `source_event_id` | `VARCHAR` | Yes | — | Optional external identifier from the originating system. |
| `subject` | `VARCHAR` | **NOT NULL** | — | Patient UPID from CloudEvent `subject`. |
| `type` | `VARCHAR` | **NOT NULL** | — | CloudEvents `type`. |
| `event_time` | `TIMESTAMPTZ` | **NOT NULL** | — | Clinical event time from CloudEvent `time`. |
| `received_at` | `TIMESTAMPTZ` | **NOT NULL** | — | Ingestion timestamp. |
| `correlation_id` | `VARCHAR` | **NOT NULL** | — | Distributed tracing ID from CloudEvent `correlationid` extension. |
| `data` | `JSONB` | **NOT NULL** | — | Full CloudEvent `data` body. See [JSONB: event data](#event_log--data). |
| `protocol_instance_id` | `UUID` | Yes | — | Matched protocol instance. `NULL` for zero-match or duplicate events. |
| `protocol_definition_id` | `UUID` | Yes | — | Matched protocol definition. `NULL` for zero-match or duplicate events. |
| `action_id` | `VARCHAR` | Yes | — | Matched protocol definition action. `NULL` for zero-match or duplicate events. |
| `facility_id` | `VARCHAR` | Yes | — | FOSA ID from CloudEvent `facilityid` extension. |
| `processing_status` | `VARCHAR` | **NOT NULL** | — | Processing outcome. See [ProcessingStatus](#processingstatus). |
| `matched_step_instance_id` | `UUID` | Yes | — | Step completed as a result of this event. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Unique | `event_log_cloudevents_id_source_key` | `(cloudevents_id, source)` — Idempotency guard. |
| Unique (partial) | `idx_event_log_source_sourceeventid` | `(source, source_event_id) WHERE source_event_id IS NOT NULL` |
| B-tree Index | `idx_event_log_subject` | `subject` — Patient-centric event queries. |
| Partial B-tree | `idx_event_log_facility` | `facility_id WHERE facility_id IS NOT NULL` — Facility-level queries. |



---

## 9. audit_log

**Immutable audit trail** for all significant system operations. Records both system-generated events (step completions, protocol enrollments, deviations) and API-driven operations (protocol loads, retirements).

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `event_category` | `VARCHAR` | **NOT NULL** | — | High-level category (e.g., `COMPLIANCE`, `PROTOCOL_MANAGEMENT`, `SECURITY`). |
| `event_type` | `VARCHAR` | **NOT NULL** | — | Specific action (e.g., `STEP_COMPLETED`, `PROTOCOL_LOADED`, `DEVIATION_DETECTED`). |
| `actor` | `VARCHAR` | Yes | — | `SYSTEM` for automated events, authenticated user identity for API calls. |
| `resource_type` | `VARCHAR` | Yes | — | Affected entity type (e.g., `StepInstance`, `ProtocolDefinition`). |
| `resource_id` | `VARCHAR` | Yes | — | Affected entity UUID (stored as VARCHAR). |
| `details` | `JSONB` | Yes | — | Event-specific context. See [JSONB: audit details](#audit_log--details). |
| `ip_address` | `VARCHAR` | Yes | — | Client IP. `NULL` for system-generated events. |
| `timestamp` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | When the action occurred. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `audit_log_pkey` | `id` |
| B-tree Index | `idx_audit_log_category` | `event_category` |
| B-tree Index | `idx_audit_log_actor` | `actor` |
| B-tree Index | `idx_audit_log_timestamp` | `timestamp` |

---

## 10. action_definition

Stores **Action Definitions** that specify what the Intelligence Service does when an intelligence rule fires. Each row represents an `ActivityDefinition` resource referenced by intelligence rules in PlanDefinition via `definitionCanonical`.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `action_type` | `VARCHAR` | **NOT NULL** | — | Type of action: `send-notification`, `create-task`, `forward-data`, `escalate`. See [ActionType](#actiontype). |
| `name` | `VARCHAR` | **NOT NULL** | — | Human-readable name (e.g., "ANC Visit Overdue Alert"). |
| `description` | `VARCHAR` | Yes | — | Description of what this action does. |
| `message_template` | `TEXT` | Yes | — | Message template with `{{variable}}` placeholders (e.g., `"Patient {{patientId}} missed {{actionId}}"`). |
| `severity` | `VARCHAR` | **NOT NULL** | — | Default severity level. See [IntelligenceSeverity](#intelligenceseverity). |
| `target` | `VARCHAR` | **NOT NULL** | — | Default target recipient. See [IntelligenceTarget](#intelligencetarget). |
| `routing` | `JSONB` | Yes | — | Routing configuration for intelligence event delivery. See [JSONB: routing](#action_definition--routing). |
| `definition_canonical` | `VARCHAR` | **NOT NULL** | — | FHIR `ActivityDefinition` canonical reference (e.g., `ActivityDefinition/send-supervisor-escalation`). Referenced by PlanDefinition intelligence rules. |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Record creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Last modification timestamp. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `action_definition_pkey` | `id` |
| Unique | `action_definition_definition_canonical_key` | `definition_canonical` — Ensures one action definition per canonical reference. |
| Check | — | `action_type IN ('send-notification', 'create-task', 'forward-data', 'escalate')` |
| Check | — | `severity IN ('low', 'medium', 'high', 'critical')` |
| Check | — | `target IN ('patient', 'assigned_worker', 'supervisor', 'facility')` |
| B-tree Index | `idx_action_definition_type` | `action_type` |

---

## 11. action_run

Tracks the **execution** of intelligence-triggered actions. Created when an intelligence rule fires and an `IntelligenceTriggerEvent` is published. Records the resolved message, delivery status, and failure reason if applicable.

### Columns

| Column | Data Type | Nullable | Default | Description |
|--------|-----------|----------|---------|-------------|
| `id` | `UUID` | **NOT NULL** | `gen_random_uuid()` | Primary key. |
| `action_definition_id` | `UUID` | **NOT NULL** | — | Foreign key → `action_definition.id`. |
| `intelligence_event_id` | `UUID` | **NOT NULL** | — | ID of the `IntelligenceTriggerEvent` published to Kafka. |
| `status` | `VARCHAR` | **NOT NULL** | — | Execution status. See [ActionRunStatus](#actionrunstatus). |
| `patient_id` | `VARCHAR` | **NOT NULL** | — | Patient identifier. |
| `protocol_instance_id` | `UUID` | Yes | — | Foreign key → `protocol_instance.id`. |
| `step_instance_id` | `UUID` | Yes | — | Foreign key → `step_instance.id`. |
| `action_type` | `VARCHAR` | **NOT NULL** | — | Action type executed (denormalized from `action_definition`). |
| `severity` | `VARCHAR` | **NOT NULL** | — | Effective severity (may be overridden by intelligence rule extension). |
| `target` | `VARCHAR` | **NOT NULL** | — | Effective target (may be overridden by intelligence rule extension). |
| `resolved_message` | `TEXT` | Yes | — | Message with `{{variable}}` placeholders resolved against runtime context. |
| `failure_reason` | `VARCHAR` | Yes | — | Reason for failure (populated when `status = 'failed'`). |
| `created_at` | `TIMESTAMPTZ` | **NOT NULL** | `now()` | Record creation timestamp. |
| `completed_at` | `TIMESTAMPTZ` | Yes | — | Completion timestamp. `NULL` for non-terminal runs. |

### Constraints & Indexes

| Type | Name | Details |
|------|------|---------|
| Primary Key | `action_run_pkey` | `id` |
| Foreign Key | `action_run_action_definition_id_fkey` | `action_definition_id` → `action_definition(id)` |
| Foreign Key | `action_run_protocol_instance_id_fkey` | `protocol_instance_id` → `protocol_instance(id)` |
| Foreign Key | `action_run_step_instance_id_fkey` | `step_instance_id` → `step_instance(id)` |
| Check | — | `status IN ('pending', 'in_progress', 'completed', 'failed', 'cancelled')` |
| B-tree Index | `idx_action_run_status` | `status` |
| B-tree Index | `idx_action_run_patient` | `patient_id` |
| B-tree Index | `idx_action_run_protocol` | `protocol_instance_id` |

---

## 12. Enumerated Value Reference

### ProtocolDefinitionStatus

| Value | Description |
|-------|-------------|
| `ACTIVE` | Protocol participates in trigger matching. New enrollments allowed. |
| `RETIRED` | Deactivated. Existing enrollments continue but no new enrollments. Trigger index entries removed. |

### ProtocolInstanceStatus

| Value | Description |
|-------|-------------|
| `ACTIVE` | Patient enrolled and protocol being tracked. |
| `COMPLETED` | All required steps completed. |
| `WITHDRAWN` | Patient manually withdrawn. |
| `EXPIRED` | Protocol exceeded maximum duration. |

### StepState

| Value | Description | Transitions From | Transitions To |
|-------|-------------|-----------------|----------------|
| `PENDING` | Created but not yet due. | *(initial)* | `DUE`, `COMPLETED` |
| `DUE` | Due date reached. | `PENDING` | `OVERDUE`, `COMPLETED` |
| `OVERDUE` | Tolerance window expired. Deviation recorded (for `must` steps). | `DUE` | `MISSED`, `SKIPPED`, `COMPLETED` |
| `MISSED` | Missed cutoff exceeded. Deviation recorded. Only for `must` steps. | `OVERDUE` | *(terminal)* |
| `COMPLETED` | Completed by a matching inbound event. | `PENDING`, `DUE`, `OVERDUE` | *(terminal)* |
| `SKIPPED` | Optional step (`requiredBehavior=could`) auto-skipped by scheduler or when a subsequent step completes. | `PENDING`, `DUE`, `OVERDUE` | *(terminal)* |

### CompletionStatus

| Value | Condition |
|-------|-----------|
| `EARLY` | `completed_at < due_date` |
| `ON_TIME` | `due_date ≤ completed_at ≤ overdue_date` (or no `due_date`) |
| `LATE` | `completed_at > overdue_date` |

### DeviationType

| Value | Trigger |
|-------|---------|
| `OVERDUE` | Scheduler transitions step `DUE` → `OVERDUE`. |
| `MISSED` | Scheduler transitions step `OVERDUE` → `MISSED`. |


### ProcessingStatus

| Value | Description |
|-------|-------------|
| `MATCHED` | Matched one or more triggers. Step instances created for all matches. |
| `ZERO_MATCH` | No trigger match or all Tier 2 conditions failed. Logged only. |
| `DUPLICATE` | Already processed (idempotency check). No processing occurs. |

### FailureStage

| Value | Description |
|-------|-------------|
| `KAFKA_PUBLISH` | Failure publishing to a Kafka topic. |
| `PROCESSING` | Failure during the matching pipeline. |
| `VALIDATION` | Failure during input validation. |

### ActionType

| Value | Description |
|-------|-------------|
| `send-notification` | Send a notification to a target (patient, worker, supervisor). |
| `create-task` | Create a task in a destination system via Receiver Adaptor. |
| `forward-data` | Forward event data to a destination system. |
| `escalate` | Escalate to a supervisor or higher authority. |

### IntelligenceSeverity

| Value | Description |
|-------|-------------|
| `low` | Informational. No immediate action required. |
| `medium` | Moderate concern. Action recommended within normal workflow. |
| `high` | Significant concern. Prompt action required. |
| `critical` | Urgent. Immediate action required. |

### IntelligenceTarget

| Value | Description |
|-------|-------------|
| `patient` | Notification directed to the patient (via patient-facing app). |
| `assigned_worker` | Notification directed to the assigned CHW or healthcare worker. |
| `supervisor` | Escalation to the supervisor or program manager. |
| `facility` | Notification to the facility (facility-level dashboard or EMR). |

### ActionRunStatus

| Value | Description |
|-------|-------------|
| `pending` | Action run created, awaiting execution by Intelligence Service. |
| `in_progress` | Intelligence Service is executing the action. |
| `completed` | Action successfully executed. |
| `failed` | Action execution failed (see `failure_reason`). |
| `cancelled` | Action run cancelled before completion. |

---

## 13. Relationships & Foreign Keys

| Parent Table | Child Table | FK Column | Cascade | Description |
|-------------|-------------|-----------|---------|-------------|
| `protocol_definition` | `protocol_instance` | `protocol_definition_id` | No cascade | Deletion prevented if instances exist. |
| `protocol_definition` | `trigger_index` | `protocol_definition_id` | Application-managed | Entries deleted when protocol retired or rebuilt. |
| `protocol_instance` | `step_instance` | `protocol_instance_id` | JPA `CascadeType.ALL` | Steps fully managed by parent. |
| `protocol_instance` | `deviation` | `protocol_instance_id` | JPA `CascadeType.ALL` | Deviations fully managed by parent. |
| `step_instance` | `deviation` | `step_instance_id` | No cascade (DB level) | Reference only; not cascade-deleted. |
| `action_definition` | `action_run` | `action_definition_id` | No cascade | Action runs reference their definition. |
| `protocol_instance` | `action_run` | `protocol_instance_id` | No cascade | Reference only. |
| `step_instance` | `action_run` | `step_instance_id` | No cascade | Reference only. |



---

## 14. JSONB Column Schemas

### protocol_definition — `definition`

The `definition` column stores the complete FHIR R4 PlanDefinition resource. Key paths used by the application:

```jsonc
{
  "resourceType": "PlanDefinition",
  "url": "http://openphc.org/fhir/PlanDefinition/anc-high-risk",
  "version": "2.1",
  "status": "active",
  "title": "ANC High-Risk Monitoring Protocol",
  "action": [
    {
      "id": "anc-visit-1",                    // → trigger_index.action_id
      "title": "ANC Visit 1",
      "trigger": [{
        "type": "data-added",
        "data": [{
          "type": "Encounter",                 // → trigger_index.resource_type
          "codeFilter": [{
            "path": "type",                    // → trigger_index.path
            "code": [{
              "system": "http://openphc.org/encounter-types",  // → trigger_index.code_system
              "code": "anc-visit"                              // → trigger_index.code_value
            }]
          }]
        }],
        "condition": {                         // Tier 2 condition (optional)
          "language": "text/jsonlogic",
          "expression": "{\">\": [{\"var\": \"resource.valueQuantity.value\"}, 140]}"
        }
      }],
      "relatedAction": [{                      // step dependencies
        "actionId": "enrollment",
        "relationship": "after-start",
        "offsetDuration": { "value": 8, "unit": "wk" }
      }],
      "timingTiming": {                        // repeating step timing
        "repeat": { "count": 6, "frequency": 1, "period": 1, "periodUnit": "mo" }
      },
      "extension": [{                          // tolerance days
        "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
        "valueInteger": 7
      }]
    }
  ]
}
```

### deviation — `metadata`

Contains deviation-type-specific timing information. 

| Field | Type | Presence | Description |
|-------|------|----------|-------------|
| `daysOverdue` | Long | OVERDUE only | Number of days past the step's `due_date` at detection time |
| `daysPastMissedDate` | Long | MISSED only | Number of days past the step's `missed_date` at detection time |

**Examples:**

| Type | Example |
|------|---------||
| OVERDUE | `{"daysOverdue": 3}` |
| MISSED | `{"daysPastMissedDate": 0}` |


### event_log — `data`

Contains the full CloudEvent `data` payload. For FHIR-based events:

```json
{
  "resourceType": "Encounter",
  "id": "9a8e5398-aaaa-4111-84a0-9e1e6e0a0001",
  "status": "finished",
  "type": [{ "coding": [{ "system": "...", "code": "anc-visit" }] }],
  "subject": { "reference": "Patient/260115-0001-7823" }
}
```

For non-FHIR events (e.g., CHW home visits):

```json
{
  "visit_type": "anc-home-visit",
  "status": "completed",
  "chw_id": "CHW-MUSANZE-042",
  "blood_pressure": { "systolic": 135, "diastolic": 85 }
}
```

### audit_log — `details`

Content varies by audit event type:

| Event Type | Example |
|------------|---------|
| STEP_COMPLETED | `{"protocolInstanceId": "pi-uuid-...", "actionId": "anc-visit-1", "completionStatus": "ON_TIME"}` |
| PROTOCOL_LOADED | `{"url": "http://openphc.org/.../anc-high-risk", "version": "2.1", "actionCount": 9, "triggerIndexEntries": 24}` |
| INTELLIGENCE_PUBLISHED | `{"intelligenceEventId": "itrig-uuid-...", "deviationType": "overdue", "severity": "high", "target": "supervisor"}` |

### action_definition — `routing`

Routing configuration for intelligence event delivery to Receiver Adaptors.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `adaptorId` | String | Yes | Identifier of the Receiver Adaptor (e.g., `smartcare-chw-adaptor`) |
| `deliveryMode` | String | Yes | Delivery mechanism: `webhook` (push) or `topic` (pull via Kafka topic subscription) |
| `endpoint` | String | Conditional | Webhook URL for push delivery. Required when `deliveryMode = "webhook"`. |
| `topicName` | String | Conditional | Kafka topic name for pull delivery. Required when `deliveryMode = "topic"`. |

**Examples:**

```json
{
  "adaptorId": "smartcare-chw-adaptor",
  "deliveryMode": "webhook",
  "endpoint": "https://adaptor.example.org/notifications"
}
```

```json
{
  "adaptorId": "facility-emr-adaptor",
  "deliveryMode": "topic",
  "topicName": "cce.actions.facility-emr"
}
```
