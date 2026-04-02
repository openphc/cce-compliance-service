# Flow Diagrams

This document provides detailed sequence and flow diagrams for all major workflows in the CCE Compliance Service.

---

## 1. Inbound Clinical Event Processing (End-to-End)

This is the primary workflow — processing a clinical event from Kafka through the entire compliance engine pipeline.

```mermaid
sequenceDiagram
    autonumber
    participant EHR as CCE Collector Service
    participant Kafka as Apache Kafka
    participant Consumer as InboundEventConsumer
    participant Engine as ComplianceEngine
    participant EventLog as EventLogService
    participant TriggerMatch as TriggerMatchingService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService<br/>(JSONLogic + FHIRPath)
    participant ProtoInst as ProtocolInstanceService
    participant StepInst as StepInstanceService
    participant Audit as AuditService
    participant DB as PostgreSQL

    EHR->>Kafka: Publish clinical event<br/>(CloudEvents v1.0)
    Kafka->>Consumer: Poll cce.events.inbound
    Consumer->>Consumer: Set MDC correlationId
    Consumer->>Engine: processInboundEvent(cloudEvent)

    rect rgb(240, 248, 255)
        Note over Engine,DB: Step 1 — Idempotency Check
        Engine->>EventLog: isDuplicate(cloudeventsId, source)
        EventLog->>DB: SELECT EXISTS(cloudeventsId, source)
        DB-->>EventLog: true/false
        EventLog-->>Engine: isDuplicate result
    end

    alt Duplicate Event
        Engine-->>Consumer: Skip (increment duplicate counter)
    else New Event
        rect rgb(245, 255, 245)
            Note over Engine,DB: Step 2 — Record Event
            Engine->>EventLog: recordEvent(cloudEvent, ZERO_MATCH)
            EventLog->>DB: INSERT INTO event_log
            DB-->>EventLog: EventLog entity
            EventLog-->>Engine: eventLog
        end

        rect rgb(255, 248, 240)
            Note over Engine: Step 3 — Extract Resource Info
            Engine->>Engine: extractResourceType(data)
            Engine->>Engine: extractAllCodes(data)
            Note over Engine: Extracts codes from code, type,<br/>category, clinicalStatus fields
        end

        rect rgb(248, 240, 255)
            Note over Engine,DB: Step 4 — Tier 1 Structural Match
            Engine->>TriggerMatch: findStructuralMatches(type, system, code)
            TriggerMatch->>DB: SELECT FROM trigger_index<br/>WHERE resource_type AND code
            DB-->>TriggerMatch: List<TriggerIndex>
            TriggerMatch-->>Engine: structural matches
        end

        rect rgb(255, 245, 245)
            Note over Engine,ExprEval: Step 5 — Tier 2 Condition Evaluation
            loop For each structural match
                Engine->>Parser: parseFromMap(definition)
                Parser-->>Engine: PlanDefinition
                Engine->>TriggerMatch: evaluateCondition(action, variables)
                TriggerMatch->>ExprEval: evaluate(language, expression, vars)
                ExprEval-->>TriggerMatch: boolean
                TriggerMatch-->>Engine: condition result
            end
        end

        rect rgb(240, 255, 240)
            Note over Engine,Audit: Step 6 — Process Result
            alt Single Match
                Engine->>ProtoInst: enrollOrGetActive(patientId, planDef)
                ProtoInst->>DB: Find or create ProtocolInstance
                DB-->>ProtoInst: ProtocolInstance
                ProtoInst-->>Engine: protocolInstance

                Engine->>StepInst: createStep(protocol, actionId, ...)
                StepInst->>DB: INSERT INTO step_instance
                Engine->>StepInst: completeStep(stepId, eventLogId, source)
                StepInst->>DB: UPDATE step_instance SET state=COMPLETED

                Note over Engine,DB: Progressive Step Instantiation
                Engine->>Parser: findDependentActions(allActions, actionId)
                Parser-->>Engine: dependent actions
                loop For each dependent action
                    Engine->>Parser: computeRelatedActionOffset(action, actionId)
                    Note over StepInst: Relationship determines base time:<br/>after-end → completedAt, after-start → dueDate
                    Note over StepInst: If TimingInfo.count > 1 → create N recurring<br/>instances with staggered due dates
                    Engine->>StepInst: createDependentSteps(protocol, depAction, base, offset)
                    StepInst->>DB: INSERT INTO step_instance(s) (state=PENDING)
                end

                Note over Engine,Audit: Intelligence Rule Evaluation (on completion)
                Engine->>Engine: evaluateIntelligenceRules(step, completion context)
                Note over Engine: If step has nested sub-actions with<br/>conditions, evaluate against completion context<br/>(e.g., late completion alerts)

                Engine->>EventLog: updateMatchResult(MATCHED)
                Engine->>Audit: auditSystem("event.processing", "matched", ...)
            else No Matches
                Engine->>EventLog: updateMatchResult(ZERO_MATCH)
            end
        end
    end

    Consumer->>Kafka: Acknowledge offset
```

## 2. Protocol Definition Loading Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Controller as ProtocolDefinitionController
    participant Service as ProtocolDefinitionService
    participant Parser as PlanDefinitionParser
    participant Validator as FhirResourceValidator
    participant DB as PostgreSQL
    participant Audit as AuditService

    Client->>Controller: POST /v1/protocol-definitions<br/>{ planDefinitionJson: "..." }
    Controller->>Service: loadProtocolDefinition(json)

    Service->>Parser: parse(json)
    Parser->>Parser: FhirContext.parseResource()
    Parser-->>Service: PlanDefinition

    Service->>Validator: validateOrThrow(planDefinition, "PlanDefinition")
    alt Validation Fails
        Validator-->>Service: throw FhirValidationException
        Service-->>Controller: propagate exception
        Controller-->>Client: 422 Unprocessable Entity
    end

    Service->>Service: Extract url & version
    Service->>DB: existsByUrlAndVersion(url, version)
    alt Already Exists
        DB-->>Service: true
        Service-->>Controller: throw IllegalArgumentException
        Controller-->>Client: 400 Bad Request
    end

    Service->>DB: Save ProtocolDefinitionEntity<br/>(status=ACTIVE, definition=JSONB)
    DB-->>Service: saved entity

    rect rgb(245, 255, 245)
        Note over Service,DB: Build Trigger Index
        Service->>Parser: extractAllActions(planDefinition)
        Parser-->>Service: List<Action>

        loop For each Action
            Service->>Parser: extractTriggers(action)
            loop For each Trigger
                Service->>Parser: extractResourceType(trigger)
                Service->>Parser: extractCodeFilters(trigger)
                loop For each CodeFilter
                    Service->>DB: Save TriggerIndex entry<br/>(resourceType, system, code, protocolDefId, actionId)
                end
            end
        end
    end

    Service->>Audit: auditSystem("protocol.definition", "loaded", ...)
    Service-->>Controller: ProtocolDefinitionEntity
    Controller-->>Client: 201 Created + ProtocolDefinitionDto
```

## 3. Scheduler-Driven State Transitions

> The Scheduler Service polls `step_instance` for time-threshold crossings and publishes trigger messages to Kafka. See [Architecture Overview §1.1](architecture-overview.md#11-scheduler-service-contract) for the polling query, lease mechanism, and ownership boundaries. The diagram below shows the Compliance Service side — receiving and processing those triggers.

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as CCE Scheduler Service
    participant Kafka as Apache Kafka
    participant Consumer as SchedulerTriggerConsumer
    participant StepSvc as StepInstanceService
    participant DevSvc as DeviationService
    participant DB as PostgreSQL

    Scheduler->>Kafka: Publish SchedulerTriggerMessage
    Kafka->>Consumer: Poll cce.scheduler.triggers
    Consumer->>StepSvc: applySchedulerTransition(message)

    StepSvc->>DB: findById(stepInstanceId)
    DB-->>StepSvc: StepInstance

    alt PENDING_TO_DUE
        StepSvc->>StepSvc: Verify state == PENDING
        StepSvc->>DB: UPDATE state = DUE
    else DUE_TO_OVERDUE
        StepSvc->>StepSvc: Verify state == DUE
        StepSvc->>DB: UPDATE state = OVERDUE
        StepSvc->>DevSvc: recordDeviation(OVERDUE)
        DevSvc->>DB: INSERT INTO deviation
    else OVERDUE_TO_MISSED
        StepSvc->>StepSvc: Verify state == OVERDUE
        alt requiredBehavior == could
            StepSvc->>DB: UPDATE state = SKIPPED
            Note right of StepSvc: No deviation for optional steps
        else requiredBehavior == must (or null)
            StepSvc->>DB: UPDATE state = MISSED
            StepSvc->>DevSvc: recordDeviation(MISSED)
            DevSvc->>DB: INSERT INTO deviation
        end
    end

    Consumer->>Kafka: Acknowledge offset
```

## 4. Protocol Enrollment Flow

```mermaid
flowchart TD
    A["Inbound Event<br/>Matched to Protocol Definition Action"] --> B{"Patient has<br/>active protocol?"}

    B -->|"Yes"| C["Return existing<br/>ProtocolInstance"]
    B -->|"No"| D["Create new<br/>ProtocolInstance"]

    D --> E["Set status = ACTIVE"]
    E --> F["Set enrolledAt = now()"]
    F --> G["Link to ProtocolDefinitionEntity"]
    G --> H["Set protocolCanonical = url|version"]

    C --> I["Check for existing<br/>active step"]
    H --> I

    I -->|"Active step exists<br/>for this actionId"| J["Use existing<br/>StepInstance"]
    I -->|"No active step"| K["Create new<br/>StepInstance"]

    K --> L["Calculate repeatIndex"]
    L --> M{"dueDate<br/>provided?"}
    M -->|"Yes"| N["state = PENDING"]
    M -->|"No"| O["state = DUE"]

    J --> P["completeStep()"]
    N --> P
    O --> P

    P --> Q{"Determine<br/>CompletionStatus"}
    Q -->|"completedAt < dueDate"| R["EARLY"]
    Q -->|"dueDate ≤ completedAt ≤ overdueDate"| S["ON_TIME"]
    Q -->|"completedAt > overdueDate"| T["LATE"]
    Q -->|"No dueDate"| U["ON_TIME (default)"]

    R --> V["Set state = COMPLETED"]
    S --> V
    T --> V
    U --> V

    V --> W["Set matchedEventId"]
    W --> X["Update Event Log<br/>matchedStepInstanceId"]
```

## 5. Deviation Detection & Intelligence Rule Evaluation

When a step transitions to `OVERDUE` or `MISSED`, the system records a deviation and evaluates intelligence rules defined on that step in the PlanDefinition.

```mermaid
flowchart TD
    subgraph "Deviation Triggers"
        T1["Scheduler: DUE → OVERDUE"]
        T2["Scheduler: OVERDUE → MISSED"]
    end

    T1 -->|"type=OVERDUE"| RD
    T2 -->|"type=MISSED"| RD

    RD["DeviationService.recordDeviation()"]
    RD --> D1["Create Deviation entity"]
    D1 --> D2["Set deviationType"]
    D2 --> D3["Set detectedAt = now()"]
    D3 --> D4["Build metadata:<br/>daysOverdue/daysPastMissedDate"]
    D4 --> D5["Link to ProtocolInstance + StepInstance"]
    D5 --> D6["Persist to DB"]
    D6 --> INTEL["Evaluate Intelligence Rules"]

    INTEL --> IR1{"Step has nested<br/>intelligence sub-actions?"}
    IR1 -->|"No"| D7["Audit: DEVIATION_DETECTED"]
    IR1 -->|"Yes"| IR2["Build evaluation context:<br/>stepState, daysOverdue, patient, protocol"]

    IR2 --> IR3["For each intelligence rule"]
    IR3 --> IR4{"Evaluate condition<br/>(JSONLogic/FHIRPath)"}
    IR4 -->|"false"| IR3
    IR4 -->|"true"| IR5["Resolve definitionCanonical<br/>→ ActionDefinition"]
    IR5 --> IR6["Build IntelligenceTriggerEvent"]
    IR6 --> IR7["Publish to cce.intelligence.triggers"]
    IR7 --> IR8["Create ActionRun record"]
    IR8 --> IR9["Set intelligence_event_id<br/>on Deviation"]
    IR9 --> D7

    D7 --> DONE["Done"]
```

## 6. Intelligence Rule Evaluation — Detailed Sequence

```mermaid
sequenceDiagram
    autonumber
    participant StepSvc as StepInstanceService
    participant DevSvc as DeviationService
    participant IntelSvc as IntelligenceRuleService
    participant Parser as PlanDefinitionParser
    participant ExprEval as ExpressionEvaluationService
    participant Producer as IntelligenceTriggerProducer
    participant ActionSvc as ActionDefinitionService
    participant DB as PostgreSQL
    participant Kafka as Apache Kafka

    StepSvc->>DevSvc: recordDeviation(step, type)
    DevSvc->>DB: INSERT INTO deviation
    DB-->>DevSvc: Deviation entity

    DevSvc->>IntelSvc: evaluateIntelligenceRules(step, deviation, protocol)

    IntelSvc->>Parser: getIntelligenceSubActions(actionDefinition)
    Parser-->>IntelSvc: List<Action> (nested sub-actions)

    loop For each intelligence sub-action
        IntelSvc->>IntelSvc: Build evaluation context<br/>(stepState, daysOverdue, patient, protocol)
        IntelSvc->>ExprEval: evaluate(condition, context)
        ExprEval-->>IntelSvc: boolean result

        alt Condition is true
            IntelSvc->>IntelSvc: Extract severity & target from extensions
            IntelSvc->>ActionSvc: resolveByCanonical(definitionCanonical)
            ActionSvc->>DB: SELECT FROM action_definition WHERE definition_canonical = ?
            DB-->>ActionSvc: ActionDefinition
            ActionSvc-->>IntelSvc: ActionDefinition (or null)

            IntelSvc->>IntelSvc: Build IntelligenceTriggerEvent
            IntelSvc->>Producer: publish(event)
            Producer->>Kafka: Send to cce.intelligence.triggers<br/>(key = protocolInstanceId)
            Kafka-->>Producer: ack

            IntelSvc->>DB: INSERT INTO action_run (status=pending)
            IntelSvc->>DB: UPDATE deviation SET intelligence_event_id = ?
        end
    end

    IntelSvc-->>DevSvc: intelligence evaluation complete
    DevSvc->>DB: Audit: DEVIATION_DETECTED + INTELLIGENCE_PUBLISHED
```

## 8. REST API Request Flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Security as SecurityConfig<br/>(JWT Filter)
    participant Controller as REST Controller
    participant Service as Service Layer
    participant DB as PostgreSQL
    participant Mapper as DtoMapper
    participant ExHandler as GlobalExceptionHandler

    Client->>Security: HTTP Request + Bearer JWT
    Security->>Security: Validate JWT signature<br/>Extract scopes
    alt Invalid Token
        Security-->>Client: 401 Unauthorized
    end
    alt Insufficient Scope
        Security-->>Client: 403 Forbidden
    end

    Security->>Controller: Authenticated request

    alt Normal Flow
        Controller->>Service: Business operation
        Service->>DB: Query/Mutate
        DB-->>Service: Result
        Service-->>Controller: Entity/List
        Controller->>Mapper: toDto(entity)
        Mapper-->>Controller: DTO
        Controller-->>Client: 200 OK / 201 Created
    else Error Flow
        Controller->>Service: Business operation
        Service-->>Controller: throw Exception
        Controller->>ExHandler: Exception propagation
        alt NoSuchElementException
            ExHandler-->>Client: 404 Not Found
        else IllegalArgumentException
            ExHandler-->>Client: 400 Bad Request
        else IllegalStateException
            ExHandler-->>Client: 409 Conflict
        else MethodArgumentNotValidException
            ExHandler-->>Client: 400 + field errors
        else FhirValidationException
            ExHandler-->>Client: 422 + validation errors
        else ExpressionEvaluationException
            ExHandler-->>Client: 422 + expression error
        else Exception
            ExHandler-->>Client: 500 Internal Server Error
        end
    end
```

## 9. Kafka Consumer Error Handling

```mermaid
flowchart TD
    A["Kafka delivers message"] --> B["Consumer receives message"]
    B --> C{"Deserialization OK?"}
    C -->|"No"| D["ErrorHandlingDeserializer<br/>wraps error"]
    D --> D2["Route to DLQ"]

    C -->|"Yes"| F["Set MDC correlationId"]
    F --> G["Delegate to service"]
    G --> H{"Processing OK?"}
    H -->|"Yes"| I["Acknowledge offset"]
    H -->|"No"| J["Increment error counter"]
    J --> K{"Retries remaining?<br/>(default: 3)"}
    K -->|"Yes"| L["Wait backoff (1s)"]
    L --> G
    K -->|"No"| M["Publish to &lt;topic&gt;.dlq"]
    M --> N["Acknowledge original offset"]
    N --> O["Log DLQ routing"]
```
