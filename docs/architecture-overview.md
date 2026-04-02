# Architecture & Design

## 1. System Context

The **CCE Compliance Service** is a core microservice within the **Clinical Compliance Engine (CCE)** platform. It tracks patient adherence to clinical protocols defined as FHIR R4 `PlanDefinition` resources — consuming clinical events, matching them against protocol steps, detecting deviations, and generating intelligence events. Intelligence rules are modeled as nested sub-actions within PlanDefinition steps, evaluated against step runtime state, and published to Kafka for downstream consumption by the CCE Intelligence Service.

```mermaid
graph TB
    subgraph External Systems
        INTEL["CCE Intelligence Service"]
        EHR["CCE Collector Service"]
        SCHEDULER["CCE Scheduler Service"]
        GATEWAY["CCE API Gateway<br/>(Auth & Routing)"]
    end

    subgraph CCE Compliance Service
        API["REST API<br/>(Spring MVC)"]
        ENGINE["Compliance Engine<br/>(Core Orchestrator)"]
        KAFKA_C["Kafka Consumers"]
        KAFKA_P["Kafka Producers"]
        INTEL_EVAL["Intelligence Rule<br/>Evaluator"]
        FHIR["FHIR Parser<br/>(FHIR R4 Libraries)"]
        EXPR["Expression Evaluator<br/>(JSONLogic + FHIRPath)"]
        DB[("PostgreSQL 16<br/>+ JSONB")]
    end

    subgraph Message Broker
        KAFKA["Apache Kafka"]
    end

    EHR -->|"Clinical Events"| KAFKA
    SCHEDULER -->|"Timer Triggers"| KAFKA
    KAFKA -->|"cce.events.inbound"| KAFKA_C
    KAFKA -->|"cce.scheduler.triggers"| KAFKA_C
    KAFKA_C --> ENGINE
    ENGINE --> FHIR
    ENGINE --> EXPR
    ENGINE --> DB
    ENGINE --> INTEL_EVAL
    INTEL_EVAL --> EXPR
    ENGINE --> KAFKA_P
    KAFKA_P -->|"cce.intelligence.triggers"| INTEL
    API --> ENGINE
    API --> DB
    GATEWAY -->|"Authenticated Requests"| API

    classDef service fill:#4A90D9,stroke:#2C5F8A,color:white
    classDef external fill:#7B8D8E,stroke:#566573,color:white
    classDef data fill:#27AE60,stroke:#1E8449,color:white
    classDef broker fill:#E67E22,stroke:#D35400,color:white

    class API,ENGINE,KAFKA_C,KAFKA_P,FHIR,EXPR,INTEL_EVAL service
    class EHR,SCHEDULER,INTEL,GATEWAY external
    class DB data
    class KAFKA broker
```

**This service does NOT handle:** event collection/ingestion (CCE Collector Service), scheduling (CCE Scheduler Service), intelligence event delivery/routing (CCE Intelligence Service), or authentication/authorization (handled by the API Gateway). Intelligence rules are evaluated within this service and published as intelligence trigger events to Kafka; the Intelligence Service handles downstream delivery (webhooks, topic subscriptions) and action execution.

### 1.1 Scheduler Service Contract

The **CCE Scheduler Service** is a headless background service with no REST API. It drives time-based step state transitions by polling the Compliance Service's `step_instance` table and publishing trigger messages to Kafka. Communication between the two services is **exclusively via Kafka** — there are no direct service-to-service HTTP calls.

```mermaid
sequenceDiagram
    participant DB as PostgreSQL<br/>(owned by Compliance Service)
    participant Scheduler as CCE Scheduler Service
    participant Kafka as Apache Kafka
    participant Consumer as SchedulerTriggerConsumer<br/>(Compliance Service)
    participant StepSvc as StepInstanceService

    loop Polling interval
        Scheduler->>DB: Poll step_instance for due transitions
        Note over Scheduler,DB: SELECT where state/date thresholds met<br/>Lease via scheduler_lease to prevent duplicates
        DB-->>Scheduler: Steps needing transition

        loop For each step
            Scheduler->>Kafka: Publish SchedulerTriggerMessage<br/>(stepInstanceId, transitionType, triggeredAt)
        end
    end

    Kafka->>Consumer: Deliver to cce.scheduler.triggers
    Consumer->>StepSvc: applySchedulerTransition()
    StepSvc->>DB: UPDATE step_instance state
```

#### Polling Query

The Scheduler Service identifies steps that need transitions using:

```sql
SELECT s FROM StepInstance s WHERE
  (s.state = 'PENDING' AND s.dueDate <= :now) OR
  (s.state = 'DUE' AND s.overdueDate <= :now) OR
  (s.state = 'OVERDUE' AND s.missedDate <= :now)
ORDER BY COALESCE(s.dueDate, s.overdueDate, s.missedDate) ASC
```

Each condition maps to a specific `transitionType`:

| Condition | Transition Type | Effect on Compliance Service |
|---|---|---|
| `state = PENDING` AND `dueDate ≤ now` | `PENDING_TO_DUE` | Step becomes actionable |
| `state = DUE` AND `overdueDate ≤ now` | `DUE_TO_OVERDUE` | Deviation recorded (`OVERDUE`) |
| `state = OVERDUE` AND `missedDate ≤ now` | `OVERDUE_TO_MISSED` | `MISSED` (must) or `SKIPPED` (could) |

#### Ownership & Coordination

| Aspect | Owner | Details |
|---|---|---|
| **`step_instance` table** | Compliance Service | Schema, writes, Flyway migrations |
| **`scheduler_lease` table** | Scheduler Service | Prevents duplicate trigger publishing across Scheduler instances |
| **Polling reads** | Scheduler Service | Read-only access to `step_instance` (state, dueDate, overdueDate, missedDate) |
| **State writes** | Compliance Service | Only the Compliance Service updates `step_instance.state` — the Scheduler never writes to it |
| **Kafka topic** | Shared | `cce.scheduler.triggers` — Scheduler produces, Compliance consumes |

> **Key invariant:** The Scheduler Service is a **read-only observer** of `step_instance`. It detects when a time threshold is crossed and notifies the Compliance Service via Kafka. The Compliance Service is the sole authority for state transitions — this ensures all business rules (requiredBehavior, deviation recording, auto-skip) are enforced in one place.

## 2. Technology Stack

| Category | Technology | Version | Purpose |
|---|---|---|---|
| **Runtime** | Java | 21 LTS | Language runtime |
| **Framework** | Spring Boot | 3.4.2 | Application framework |
| **Persistence** | Spring Data JPA / Hibernate | 6.x | ORM and data access |
| **Database** | PostgreSQL | 16 | JSONB, GIN indexes |
| **Migration** | Flyway | 10.x | Schema version management |
| **JSONB Mapping** | Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)` | 6.x | Native JPA ↔ PostgreSQL JSONB |
| **Messaging** | Spring Kafka | 3.x | Event-driven messaging |
| **FHIR** | FHIR Libraries | 4.0.1 | FHIR R4 PlanDefinition parsing & validation |
| **Expression** | Apache Johnzon JsonLogic | 2.0.2 | Tier 2 conditional evaluation (JSONLogic) |
| **Metrics** | Micrometer + Prometheus | 1.x | Application metrics |
| **Tracing** | OpenTelemetry | 1.x | Distributed tracing |
| **Testing** | JUnit 5 + Mockito | 5.x / 5.x | Unit testing with mocked dependencies |

## 3. Package Structure

```
org.openphc.cce.compliance
├── ComplianceServiceApplication.java          # @SpringBootApplication entry point
├── config/                                    # AppConfig, ObservabilityConfig
├── domain/
│   ├── entity/                                # 7 JPA entities
│   ├── enums/                                 # 6 value-based enums
│   └── repository/                            # 7 Spring Data JPA repositories
├── fhir/                                      # FHIR parsing, JSONLogic & FHIRPath evaluation
├── kafka/
│   ├── config/                                # Consumer/Producer factories, topic bindings
│   ├── consumer/                              # InboundEventConsumer, SchedulerTriggerConsumer
│   ├── model/                                 # CloudEventMessage, IntelligenceTriggerEvent
│   └── producer/                              # IntelligenceTriggerProducer
├── service/                                   # 9 business logic services + 3 supporting records
└── web/                                       # Controllers, DTOs, DtoMapper, ExceptionHandler
```

## 4. Core Pipeline — ComplianceEngine

The `ComplianceEngine` is the central orchestrator. All inbound event processing flows through it:

```mermaid
flowchart TD
    START["CloudEventMessage received"] --> S1

    S1["Step 1: Idempotency Check<br/>(cloudeventsId, source)"]
    S1 -->|"Duplicate"| DUP["Return early"]
    S1 -->|"New"| S2

    S2["Step 2: Record Event Log"] --> S3
    S3["Step 3: Extract Resource Info<br/>from payload (data)"] --> EXPL

    EXPL{"Step 4: Explicit Match?<br/>(actionId on CloudEvent)"}
    EXPL -->|"Yes"| EXPLM["processExplicitMatch()<br/>Bypass matching"]
    EXPL -->|"No"| S5
    EXPLM --> DONE["Return"]

    S5["Step 5: Two-Tier Matching<br/>(see §5.4 for detailed flow)"] --> S6

    S6{"Result Classification"}
    S6 -->|"≥1 matches"| MATCH["For each match:<br/>Enroll patient → Create/complete step<br/>→ Progressive step instantiation<br/>→ Evaluate intelligence rules"]
    S6 -->|"0 matches"| ZERO["Log ZERO_MATCH"]
```

### 4.1 Resource Extraction

Resource metadata is extracted from the CloudEvent **payload** (`data`), never from the envelope:

| Field | Extraction Paths |
|---|---|
| `resourceType` | `data.resourceType` (e.g., `"Observation"`, `"Encounter"`) |
| `allCodes` | `data.code.coding[*]`, `data.type.coding[*]`, `data.category[*].coding[*]`, `data.clinicalStatus.coding[*]`, `data.status` |

## 5. Two-Tier Matching Algorithm

### 5.1 Tier 1 — Structural Match

Inverted index lookup on the `trigger_index` table using `GROUP BY` + `HAVING` to enforce **AND semantics** across all `codeFilter` entries:

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

The `:codeTriples` parameter is a list of `path|system|code` strings extracted from the inbound event payload. The correlated subquery counts the **total** distinct paths each action requires, ensuring actions with different numbers of codeFilters are correctly evaluated in a single query.

The index is built at protocol load time by decomposing each action's `TriggerDefinition.data[].codeFilter[]` into `(resourceType, path, codeSystem, codeValue, protocolDefinitionId, actionId)` rows.

### 5.2 Condition-Only Triggers

Triggers that have no `data[]` section (only a `condition`) are **not indexed** in `trigger_index`. They are held in-memory and evaluated via Tier 2 for every inbound event. These are validated at protocol load time — a trigger with no `data[]` and no `condition` is rejected.

### 5.3 Tier 2 — Condition Evaluation

For each Tier 1 candidate, evaluates the trigger's `condition` expression.  **Triggers with no `condition` pass automatically**.

| Variable | Source |
|---|---|
| `event` | CloudEvent data payload |
| `patient` | Patient context (`patientId`, demographics) |
| `step` | Current step context (`actionId`, `repeatIndex`, `state`) |
| `protocol` | Protocol context (`protocolCanonical`, `status`) |

**Supported languages:**
- `text/jsonlogic` — via Apache Johnzon `JsonLogic`
- `text/fhirpath` — via FHIR `IFhirPath` engine (R4)
- Any other — rejected with `UnsupportedExpressionLanguageException`

### 5.4 How Matching Works — Step by Step

> **Terminology:** In FHIR, `PlanDefinition.action[]` defines the steps of a protocol. In CCE, each `action` is a **step definition** — a template that becomes a `step_instance` when matched for a specific patient. Throughout this section, "step definition" and "action" are used interchangeably.

A trigger definition has three filter components. Each component is **independent** — a trigger may use any combination:

| Component | FHIR Path | What it checks |
|---|---|---|
| **F1** — Resource type | `trigger.data[].type` | Does the payload's `resourceType` match? (e.g., `Encounter`) |
| **F2** — Code filters | `trigger.data[].codeFilter[]` | Do the payload's coded fields match the required `(path, system, code)` tuples? |
| **F3** — Condition | `trigger.condition` | Does the payload satisfy a JSONLogic/FHIRPath expression? |

> **F1 is implicit:** Every trigger that has a `data[]` section always has `data[].type` (the FHIR resource type). So F1 is present whenever F2 is present. A trigger with no `data[]` at all is a **condition-only trigger** (F3 only).

#### Five Exclusive Matching Scenarios

Every trigger in the system falls into **exactly one** of these five scenarios:

```mermaid
flowchart TD
    EVENT["Inbound CloudEvent"] --> F1_CHECK{"F1: Does payload resourceType match any trigger data[].type?"}

    F1_CHECK -->|"Yes"| HAS_F2{"Has F2? (codeFilter entries)"}
    F1_CHECK -->|"No"| F3_ONLY{"F3-only triggers (condition-only, held in-memory)"}

    HAS_F2 -->|"Yes"| TIER1["Tier 1 Query: GROUP BY + HAVING enforces ALL codeFilters match"]
    HAS_F2 -->|"No"| HAS_F3_NOFILT{"Has F3? (condition)"}

    HAS_F3_NOFILT -->|"No"| S1["Scenario 1 (F1) Match on resource type alone ⚠ Broadest match"]
    HAS_F3_NOFILT -->|"Yes"| TIER2_F1F3["Tier 2: Evaluate condition against payload"]

    TIER2_F1F3 -->|"true"| S3_ALT["Scenario 3 (F1,F3) Step created"]
    TIER2_F1F3 -->|"false"| REJECT3["No match — eliminated"]

    TIER1 --> TIER1_RESULT["Tier 1 Result Set (step definitions matching F1+F2)"]

    TIER1_RESULT --> HAS_F3{"Has F3? (condition)"}
    HAS_F3 -->|"No"| S2["Scenario 2 (F1,F2) Step created"]
    HAS_F3 -->|"Yes"| TIER2["Tier 2: Evaluate condition against payload"]

    TIER2 -->|"true"| S4["Scenario 4 (F1,F2,F3) Step created"]
    TIER2 -->|"false"| REJECT["No match — eliminated"]

    F3_ONLY --> EVAL_F3["Tier 2: Evaluate condition against payload"]
    EVAL_F3 -->|"true"| S5["Scenario 5 (F3 only) Step created"]
    EVAL_F3 -->|"false"| REJECT2["No match — eliminated"]

    style S1 fill:#E67E22,stroke:#D35400,color:white
    style S2 fill:#27AE60,stroke:#1E8449,color:white
    style S3_ALT fill:#27AE60,stroke:#1E8449,color:white
    style S4 fill:#27AE60,stroke:#1E8449,color:white
    style S5 fill:#27AE60,stroke:#1E8449,color:white
    style REJECT fill:#E74C3C,stroke:#C0392B,color:white
    style REJECT2 fill:#E74C3C,stroke:#C0392B,color:white
    style REJECT3 fill:#E74C3C,stroke:#C0392B,color:white
```

| Scenario | Components | Trigger Shape | Matching Path | Step Created When |
|---|---|---|---|---|
| **1** | **(F1)** | `data[].type` only — no `codeFilter[]`, no `condition` | Resource type match only | Payload `resourceType` matches trigger `data[].type`. **Broadest match** — every event of that type triggers a step. |
| **2** | **(F1,F2)** | `data[].type` + `codeFilter[]`, no `condition` | Tier 1 (GROUP BY + HAVING) | All code filters match — **no further evaluation needed** |
| **3** | **(F1,F3)** | `data[].type` + `condition`, no `codeFilter[]` | Resource type match → Tier 2 | `resourceType` matches AND condition evaluates to `true` |
| **4** | **(F1,F2,F3)** | `data[].type` + `codeFilter[]` + `condition` | Tier 1 → **reuses Tier 1 result** → Tier 2 | All code filters match AND condition evaluates to `true` |
| **5** | **(F3)** | `condition` only, no `data[]` | Tier 2 only (in-memory) | Condition evaluates to `true` (checked for **every** inbound event) |

> **Scenario 1 (F1) — caution:** A trigger with only `data[].type` and no `codeFilter[]` or `condition` will match **every** inbound event of that resource type (e.g., every `Encounter`). This is intentionally supported for use cases like "enroll patient on any encounter of this type," but protocol authors should be aware of the broad match scope.

#### Exclusivity

Each scenario is **mutually exclusive** — a trigger belongs to exactly one scenario based on which components it defines:

- Has `data[]` with `codeFilter[]` and `condition`? → **Scenario 4 (F1,F2,F3)**
- Has `data[]` with `codeFilter[]` but no `condition`? → **Scenario 2 (F1,F2)**
- Has `data[]` with only `type` (no `codeFilter[]`) and `condition`? → **Scenario 3 (F1,F3)**
- Has `data[]` with only `type` (no `codeFilter[]`) and no `condition`? → **Scenario 1 (F1)**
- Has only `condition` (no `data[]`)? → **Scenario 5 (F3)**
- Has neither `data[]` nor `condition`? → **Rejected at protocol load time**

#### Tier 1 Result Reuse

Scenarios 2 and 4 both require Tier 1 matching (F1+F2). The Tier 1 query is executed **once**, and its result set is **reused**:

1. The `trigger_index` query runs once, returning all `(protocolDefinitionId, actionId)` pairs where all code filters match.
2. For **Scenario 2** step definitions (no condition): the Tier 1 result is final — step instances are created immediately.
3. For **Scenario 4** step definitions (has condition): the same Tier 1 result is filtered through Tier 2 condition evaluation. There is **no re-query** of `trigger_index`.

```
Tier 1 Result Set ──┬── step definitions without condition ──► Scenario 2 → create step instances
                    │
                    └── step definitions with condition ──► Tier 2 eval ──► Scenario 4 → create step instances (if true)
```

#### Example Trigger (Scenario 4: F1,F2,F3)

Consider a step definition (`action`) with a trigger that requires an `Encounter` (F1) with **four** code filters (F2) and a condition (F3):

```json
"trigger": [
  {
    "type": "data-added",
    "data": [
      {
        "type": "Encounter",
        "codeFilter": [
          {
            "path": "type",
            "code": [{ "system": "http://openphc.org/encounter-types", "code": "anc-visit" }]
          },
          {
            "path": "status",
            "code": [{ "code": "finished" }]
          },
          {
            "path": "class",
            "code": [{ "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB" }]
          },
          {
            "path": "serviceType",
            "code": [{ "system": "http://openphc.org/service-types", "code": "high-risk-anc" }]
          }
        ]
      }
    ],
    "condition": {
      "language": "text/jsonlogic",
      "expression": "{\"==\": [{\"var\": \"class.code\"}, \"AMB\"]}"
    }
  }
]
```

At **protocol load time**, this trigger is decomposed into 4 `trigger_index` rows (one per `codeFilter`):

| `resource_type` | `path` | `code_system` | `code_value` |
|---|---|---|---|
| `Encounter` | `type` | `http://openphc.org/encounter-types` | `anc-visit` |
| `Encounter` | `status` | *(empty)* | `finished` |
| `Encounter` | `class` | `http://terminology.hl7.org/CodeSystem/v3-ActCode` | `AMB` |
| `Encounter` | `serviceType` | `http://openphc.org/service-types` | `high-risk-anc` |

When an inbound `Encounter` event arrives:

1. **Tier 1 (F1+F2)** — The query matches on `resource_type = 'Encounter'` and checks the inbound event's `path|system|code` triples against all 4 indexed rows. The correlated `HAVING` clause compares the matched path count against this action's total path count (4). If the payload is missing any one (e.g., no `serviceType` code), this step definition is eliminated.
2. **Tier 2 (F3)** — Since this step definition has a condition, the Tier 1 result is passed to Tier 2. The JSONLogic expression `{"==": [{"var": "class.code"}, "AMB"]}` is evaluated against the payload. Only if it returns `true` does this step definition produce a step instance.

> **Key point:** A step instance is created for **every** step definition that survives its matching scenario. If 3 different step definitions match a single inbound event (e.g., one via Scenario 1, one via Scenario 2, one via Scenario 4), 3 separate step instances are created.

## 6. State Machines

### 6.1 Step Instance

```mermaid
stateDiagram-v2
    [*] --> PENDING : createStep()
    PENDING --> DUE : scheduler(PENDING_TO_DUE)
    DUE --> OVERDUE : scheduler(DUE_TO_OVERDUE)
    OVERDUE --> MISSED : scheduler(OVERDUE_TO_MISSED)
    PENDING --> COMPLETED : completeStep()
    DUE --> COMPLETED : completeStep()
    OVERDUE --> COMPLETED : completeStep()
    OVERDUE --> SKIPPED : scheduler(OVERDUE_TO_MISSED) [could]
    COMPLETED --> [*]
    MISSED --> [*]
    SKIPPED --> [*]
```

**Completion status:** `EARLY` (before dueDate), `ON_TIME` (between due and overdue), `LATE` (after overdueDate or state was OVERDUE).

**Required behavior:** Steps with `requiredBehavior=could` (from `PlanDefinition.action.requiredBehavior`) are optional. When the scheduler fires `OVERDUE_TO_MISSED` on a `could` step, it transitions to `SKIPPED` (no deviation) instead of `MISSED`. Additionally, when any step completes, preceding `could` steps still in actionable states are auto-skipped.

### 6.2 Protocol Instance

`ACTIVE → COMPLETED | WITHDRAWN | EXPIRED`. Terminal states: `COMPLETED`, `WITHDRAWN`, `EXPIRED`.

Protocol completion is **automatic** — when all steps reach terminal states (`COMPLETED`, `MISSED`, `SKIPPED`), the protocol transitions to `COMPLETED`. There is no manual complete endpoint; `WITHDRAWN` covers manual termination.

## 7. Intelligence Rules

Intelligence rules are modeled as **nested sub-actions** within a PlanDefinition step action. They are evaluated when step state changes occur (deviation detection, step completion) and produce intelligence trigger events published to Kafka.

### 7.1 Rule Structure

Each intelligence rule is a nested `action` within a step's `action[]` array, containing:

- **Condition** — A JSONLogic expression evaluated against the step's runtime state (e.g., `stepState == 'overdue' && daysOverdue > 3`)
- **`definitionCanonical`** — Reference to an `ActivityDefinition` resource that defines the action to execute (e.g., send notification, create task, escalate)
- **Extensions** — `intelligence-severity` (`low`, `medium`, `high`, `critical`) and `intelligence-target` (`patient`, `assigned_worker`, `supervisor`, `facility`)

```json
{
  "id": "anc-visit-2-overdue-escalation",
  "condition": [{
    "kind": "applicability",
    "expression": {
      "language": "text/jsonlogic",
      "expression": "{\"and\": [{\"==\": [{\"var\": \"stepState\"}, \"overdue\"]}, {\">\": [{\"var\": \"daysOverdue\"}, 3]}]}"
    }
  }],
  "definitionCanonical": "ActivityDefinition/send-supervisor-escalation",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "high"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-target",
      "valueCode": "supervisor"
    }
  ]
}
```

### 7.2 Evaluation Context

Intelligence rule conditions are evaluated with these variables:

| Variable | Type | Source |
|---|---|---|
| `stepState` | String | Current step state (`overdue`, `missed`, `completed`, etc.) |
| `daysOverdue` | Long | Days past the step's `dueDate` |
| `daysPastMissedDate` | Long | Days past the step's `missedDate` |
| `completionStatus` | String | `early`, `on_time`, `late` (only when step is completed) |
| `event` | Map | The triggering CloudEvent data payload |
| `patient` | Map | Patient context (`patientId`) |
| `protocol` | Map | Protocol context (`protocolCanonical`, `status`) |

### 7.3 Evaluation Triggers

Intelligence rules are evaluated at two points:

1. **Deviation detection** — When the scheduler transitions a step to `OVERDUE` or `MISSED`, rules on that step are evaluated against the deviation context.
2. **Step completion** — When a step is completed (via an inbound event), rules on that step are evaluated against the completion context (e.g., to detect late completions).

### 7.4 Intelligence Event Publishing

When a rule's condition evaluates to `true`, the service:

1. Resolves the `definitionCanonical` to the registered `ActionDefinition`
2. Builds an `IntelligenceTriggerEvent` with full context (patient, protocol, step, deviation, severity, target)
3. Publishes the event to the `cce.intelligence.triggers` Kafka topic
4. Records the `intelligence_event_id` on the deviation record (if triggered by a deviation)

### 7.5 Intelligence Types

| Type | Trigger | Example |
|---|---|---|
| **Missed Event** | Step transitioned to OVERDUE or MISSED | "ANC visit due at week 20, now 5 days overdue" |
| **Late Completion** | Step completed with `completionStatus=LATE` | "Referral appointment was 8 days late (protocol: 7 days)" |
| **Escalation** | Condition evaluates severity threshold | "High-risk pregnancy patient missed 2 consecutive visits" |
| **Reminder** | Upcoming due date (scheduler-driven) | "ANC visit due in 3 days" |

### 7.6 Action Definitions

An **Action Definition** specifies what the Intelligence Service does when it receives an intelligence trigger event. Stored as `ActivityDefinition` resources in the compliance service and referenced by intelligence rules via `definitionCanonical`.

Each Action Definition specifies:
- **Action type** — What to do: `send-notification`, `create-task`, `forward-data`, `escalate`
- **Message template** — Content with variable placeholders (e.g., `"Patient {{patientId}} missed {{actionId}}"`)
- **Routing** — Which Receiver Adaptor(s) should receive the action
- **Severity** and **target** — Default severity and target (can be overridden by intelligence rule extensions)

## 8. Security

- **Authentication & Authorization:** Handled by the **CCE API Gateway**. This service does not implement security directly — all requests arrive pre-authenticated.
- Actuator endpoints are publicly accessible for health checks and monitoring.

See [API Reference](api-reference.md) for endpoint details.

## 9. Observability

### 9.1 Metrics

| Metric | Type | Description |
|---|---|---|
| `cce.events.processed` | Counter | Total inbound events processed |
| `cce.events.matched` | Counter (tagged) | By status: `matched`, `zero_match` |
| `cce.events.duplicate` | Counter | Duplicate events detected |
| `cce.events.zero_match` | Counter | Events with zero trigger matches |
| `cce.events.intelligence.published` | Counter | Intelligence trigger events published to Kafka |
| `cce.step.matching.duration` | Timer | Tier 1 + Tier 2 matching time |
| `cce.consumer.inbound.errors` | Counter | Inbound event consumer processing errors |
| `cce.consumer.scheduler.errors` | Counter | Scheduler trigger consumer processing errors |
| `cce.protocol.instances.active` | Gauge | Active protocol instances |

### 9.2 Logging & Tracing

- **Format:** `timestamp [thread] [correlationId] level logger - message`
- **Tracing:** OpenTelemetry (OTLP), `correlationId` propagated via MDC and CloudEvents extensions
- **Health:** `/actuator/health` (liveness + readiness), `/actuator/prometheus`

## 10. Error Handling

### 10.1 REST API

| Error Type | HTTP Status |
|---|---|
| Resource not found | 404 |
| Invalid input | 400 |
| State conflict | 409 |
| FHIR validation failure | 422 |
| Internal error | 500 |

### 10.2 Kafka

- **Consumer errors:** Exception propagates to `DefaultErrorHandler` → retries with 1-second fixed backoff (up to 3 attempts) → routes to DLQ topic (`<topic>.dlq`) after exhausting retries
- **Dead Letter Queue:** Failed records are published to `cce.events.inbound.dlq` or `cce.scheduler.triggers.dlq` with original headers preserved
- **Retry configuration:** `cce.kafka.retry.max-attempts` (default 3), `cce.kafka.retry.backoff-interval-ms` (default 1000)
- **Producer:** Idempotent with `acks=all`
- **Deserialization:** `ErrorHandlingDeserializer` wraps errors gracefully

## 11. Scaling

| Dimension | Strategy |
|---|---|
| **Horizontal** | Kafka consumer group enables multi-instance; partition assignment is automatic (25 partitions per topic) |
| **Database** | Connection pool per instance (20 max) |
| **Kafka** | 3 concurrent listener threads per instance; 25 partitions per topic (configurable via `cce.kafka.topics.default-partitions`) |
| **API** | Stateless — any instance serves any request |
