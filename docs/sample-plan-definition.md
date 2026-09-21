# Sample PlanDefinition — Diabetes Management (Comprehensive Example)

This document provides a complete, annotated `PlanDefinition` that exercises every feature parsed by the compliance service (`PlanDefinitionParser`), along with companion `ActivityDefinition` resources.

---

## PlanDefinition JSON

```json
{
  "resourceType": "PlanDefinition",
  "id": "diabetes-management-comprehensive",
  "url": "http://openphc.org/PlanDefinition/diabetes-management-comprehensive",
  "version": "1.0.0",
  "name": "DiabetesManagementComprehensive",
  "title": "Diabetes Care — Comprehensive Management Protocol",
  "status": "active",
  "date": "2026-04-21",
  "description": "Protocol for managing diabetic patients. Covers enrollment, HbA1c, blood glucose, foot exam, and multi-level intelligence escalation actions.",

  "action": [

    {
      "id": "enrollment",
      "title": "Enroll Patient on Diabetes Diagnosis",
      "description": "Enroll when a confirmed active diabetes Condition is created.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "diagnosis-trigger",
          "data": [
            {
              "type": "Condition",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://snomed.info/sct",          "code": "73211009" },
                    { "system": "http://hl7.org/fhir/sid/icd-10",  "code": "E11" }
                  ]
                },
                {
                  "path": "clinicalStatus",
                  "code": [
                    {
                      "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                      "code": "active"
                    }
                  ]
                }
              ]
            }
          ],
          "condition": {
            "language": "text/fhirpath",
            "expression": "Condition.verificationStatus.coding.where(code = 'confirmed').exists()"
          }
        }
      ],
      "timingTiming": {
        "repeat": { "count": 1, "frequency": 1, "period": 1, "periodUnit": "d" }
      },
      "relatedAction": [
        {
          "actionId": "hba1c-check",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 30, "unit": "d",
            "system": "http://unitsofmeasure.org", "code": "d"
          }
        }
      ]
    },

    {
      "id": "hba1c-check",
      "title": "HbA1c Monitoring",
      "description": "Quarterly HbA1c test to evaluate glycaemic control.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "hba1c-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "4548-4" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 4, "frequency": 1, "period": 90, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 7
        }
      ],
      "relatedAction": [
        {
          "actionId": "blood-glucose-check",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 7, "unit": "d",
            "system": "http://unitsofmeasure.org", "code": "d"
          }
        }
      ],
      "action": [
        {
          "id": "hba1c-elevated-alert",
          "title": "Elevated HbA1c — Worker Alert",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/fhirpath",
                "expression": "Observation.value.value > 7.0"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "MEDIUM"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "hba1c-critical-escalation",
          "title": "Critical HbA1c — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/fhirpath",
                "expression": "Observation.value.value > 10.0"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        },
        {
          "id": "hba1c-overdue-deviation",
          "title": "HbA1c Overdue — Worker Alert",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"overdue\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "hba1c-missed-deviation",
          "title": "HbA1c Missed — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"missed\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        }
      ]
    },

    {
      "id": "blood-glucose-check",
      "title": "Fasting Blood Glucose Check",
      "description": "Weekly fasting blood glucose observation.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "glucose-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "1558-6" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 12, "frequency": 1, "period": 7, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 2
        }
      ],
      "action": [
        {
          "id": "glucose-low-patient-info",
          "title": "Low Glucose — Patient Information",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"<\": [{\"var\": \"glucoseValue\"}, 4.0]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-patient-notification|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "LOW"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "PATIENT"
            }
          ]
        },
        {
          "id": "glucose-high-worker-task",
          "title": "High Glucose — Worker Follow-up Task",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"glucoseValue\"}, 11.1]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-worker-task|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "glucose-critical-facility-referral",
          "title": "Critical Glucose — Facility Emergency Referral",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"glucoseValue\"}, 22.2]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-emergency-referral|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "FACILITY"
            }
          ]
        }
      ]
    },

    {
      "id": "annual-foot-exam",
      "title": "Annual Diabetic Foot Examination",
      "description": "Mandatory annual foot exam — must not be skipped.",
      "requiredBehavior": "must",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "foot-exam-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "44963-7" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 1, "frequency": 1, "period": 365, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 14
        }
      ],
      "action": [
        {
          "id": "foot-exam-missed-supervisor",
          "title": "Missed Foot Exam — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"missed\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/foot-exam-missed-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        }
      ]
    },

    {
      "id": "any-encounter-log",
      "title": "Log Any Patient Encounter",
      "description": "Broadest match — data[] type only, no codeFilter. Produces one TriggerIndex row with empty path/system/code.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "any-encounter-trigger",
          "data": [
            { "type": "Encounter" }
          ]
        }
      ]
    },

    {
      "id": "finished-encounter-compliance",
      "title": "Compliance Check on Finished Encounter",
      "description": "Resource type match combined with an inline trigger condition (Scenario 3: F1 + F3).",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "finished-encounter-trigger",
          "data": [
            { "type": "Encounter" }
          ],
          "condition": {
            "language": "text/fhirpath",
            "expression": "Encounter.status = 'finished'"
          }
        }
      ]
    },

    {
      "id": "global-risk-assessment",
      "title": "Global Risk Score Assessment",
      "description": "Condition-only trigger — no data[]. Evaluated on every inbound event (Scenario 5: F3 only). No TriggerIndex row created.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "risk-score-trigger",
          "condition": {
            "language": "text/jsonlogic",
            "expression": "{\">=\": [{\"var\": \"riskScore\"}, 8]}"
          }
        }
      ],
      "action": [
        {
          "id": "global-risk-worker-notification",
          "title": "High Risk Score — Worker Notification",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"riskScore\"}, 8]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/risk-score-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        }
      ]
    }

  ]
}
```

---

## Companion ActivityDefinitions

Each `definitionCanonical` referenced in the PlanDefinition must have a registered `ActivityDefinition`. The `kind` field maps to the `ActionType` enum (`CommunicationRequest`, `Task`, `ServiceRequest`).

### CommunicationRequest — Supervisor Escalation

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glycaemic-supervisor-escalation",
  "url": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation",
  "version": "1.0.0",
  "name": "GlycaemicSupervisorEscalation",
  "title": "Glycaemic Supervisor Escalation",
  "status": "active",
  "kind": "CommunicationRequest",
  "description": "Sends a supervisor escalation message when glycaemic thresholds are breached or a step is missed.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "CRITICAL"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "SUPERVISOR"
    }
  ]
}
```

### Task — Worker Follow-up

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glucose-worker-task",
  "url": "http://openphc.org/ActivityDefinition/glucose-worker-task",
  "version": "1.0.0",
  "name": "GlucoseWorkerTask",
  "title": "High Glucose Worker Follow-up Task",
  "status": "active",
  "kind": "Task",
  "description": "Creates a follow-up task for the assigned community health worker when blood glucose is elevated.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "HIGH"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "ASSIGNED_WORKER"
    }
  ]
}
```

### ServiceRequest — Emergency Referral

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glucose-emergency-referral",
  "url": "http://openphc.org/ActivityDefinition/glucose-emergency-referral",
  "version": "1.0.0",
  "name": "GlucoseEmergencyReferral",
  "title": "Critical Glucose Emergency Referral",
  "status": "active",
  "kind": "ServiceRequest",
  "description": "Generates an emergency service referral to the facility when glucose is critically high.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "CRITICAL"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "FACILITY"
    }
  ]
}
```

---

## Feature Reference

| Feature | Field / Extension | Allowed Values |
|---|---|---|
| Action type coding (required on every action, all nesting levels) | `action[].type.coding` | `step` (`http://openphc.org/fhir/CodeSystem/action-type`) or `fire-event` (`http://terminology.hl7.org/CodeSystem/action-type`) |
| Trigger — resource + code filter | `trigger[].data[].codeFilter[]` | One row per code in `TriggerIndex`. `codeFilter.path` may point at either a `CodeableConcept` (`Observation.code`, `Encounter.type`, ...) or a bare `Coding` (`Encounter.class`, ...) — both are extracted correctly; see `ResourceInfoExtractor` in `architecture-overview.md` §5.1. |
| Trigger — inline condition | `trigger[].condition` | `text/fhirpath`, `text/jsonlogic` |
| Trigger — condition only (no data) | `trigger[]` with no `data[]` | Placed in `extractConditionOnlyTriggers()` |
| Intelligence sub-action condition | `action[].condition[kind=applicability]` | `text/fhirpath`, `text/jsonlogic` |
| Intelligence severity | `intelligence-severity` extension | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| Intelligence destination | `intelligence-destination` extension | Free-form string (routing destination for the Intelligence Service); this example uses `PATIENT`, `ASSIGNED_WORKER`, `SUPERVISOR`, `FACILITY` |
| Deviation type (jsonlogic var) | `event.deviationType` | `"overdue"`, `"missed"` |
| Step tolerance window | `tolerance-days` extension | Integer (days) |
| Required step | `requiredBehavior` | `must`, `could`, `must-unless-documented` — only `must` and `could` have distinct handling today (see note below) |
| Step scheduling | `timingTiming.repeat` | `count`, `frequency`, `period`, `periodUnit` |
| Action ordering | `relatedAction[].offsetDuration` | Days after predecessor action ends |
| ActivityDefinition type | `kind` | `CommunicationRequest`, `Task`, `ServiceRequest` |
| Canonical reference format | `definitionCanonical` | `<url>|<version>` |

`requiredBehavior` note: `must` drives the "must"-only predecessor backfill gating in `StepInstanceService.backfillMissingMandatorySteps` (via `PlanDefinitionParser.computeMustPredecessorSteps`); `could` lets `StepInstanceService` auto-skip an optional step instead of marking it missed. `must-unless-documented` is a valid FHIR `requiredBehavior` code and is accepted (see the `data-dictionary.md` `required_behavior` check constraint), but the parser and services do not currently branch on it — it behaves like a step with no special required-behavior handling.

---

## Trigger Matching Scenarios

| Scenario | `data[]` present | `codeFilter[]` present | `condition` present | Parser handling |
|---|---|---|---|---|
| 1 | Yes | No | No | `TriggerIndex` row with empty path/system/code |
| 2 | Yes | Yes | No | One `TriggerIndex` row per code |
| 3 | Yes | No | Yes | `TriggerIndex` row (empty) + in-memory condition check |
| 4 | Yes | Yes | Yes | `TriggerIndex` rows per code + in-memory condition check |
| 5 | No | — | Yes | `extractConditionOnlyTriggers()` — evaluated on every event |
