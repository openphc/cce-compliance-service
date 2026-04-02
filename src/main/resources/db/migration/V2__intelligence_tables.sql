-- ==============================================================================
-- CCE Compliance Service — Intelligence Tables
-- ==============================================================================
-- Flyway Migration: V2
-- Database: PostgreSQL 16
-- ==============================================================================

-- =============================================
-- 1. action_definition
-- =============================================
CREATE TABLE action_definition (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    action_type             VARCHAR         NOT NULL,
    name                    VARCHAR         NOT NULL,
    description             VARCHAR,
    message_template        TEXT,
    severity                VARCHAR         NOT NULL,
    target                  VARCHAR         NOT NULL,
    routing                 JSONB,
    definition_canonical    VARCHAR         NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT action_definition_pkey PRIMARY KEY (id),
    CONSTRAINT action_definition_canonical_key UNIQUE (definition_canonical),
    CONSTRAINT action_definition_action_type_check CHECK (action_type IN ('SEND_NOTIFICATION', 'CREATE_TASK', 'FORWARD_DATA', 'ESCALATE')),
    CONSTRAINT action_definition_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT action_definition_target_check CHECK (target IN ('PATIENT', 'ASSIGNED_WORKER', 'SUPERVISOR', 'FACILITY'))
);

CREATE INDEX idx_action_definition_canonical ON action_definition (definition_canonical);
CREATE INDEX idx_action_definition_action_type ON action_definition (action_type);

-- =============================================
-- 2. action_run
-- =============================================
CREATE TABLE action_run (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    action_definition_id    UUID            NOT NULL,
    intelligence_event_id   UUID            NOT NULL,
    status                  VARCHAR         NOT NULL,
    patient_id              VARCHAR         NOT NULL,
    protocol_instance_id    UUID,
    step_instance_id        UUID,
    action_type             VARCHAR         NOT NULL,
    severity                VARCHAR         NOT NULL,
    target                  VARCHAR         NOT NULL,
    resolved_message        TEXT,
    failure_reason          VARCHAR,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,

    CONSTRAINT action_run_pkey PRIMARY KEY (id),
    CONSTRAINT action_run_action_definition_fk FOREIGN KEY (action_definition_id)
        REFERENCES action_definition (id),
    CONSTRAINT action_run_protocol_instance_fk FOREIGN KEY (protocol_instance_id)
        REFERENCES protocol_instance (id),
    CONSTRAINT action_run_step_instance_fk FOREIGN KEY (step_instance_id)
        REFERENCES step_instance (id),
    CONSTRAINT action_run_status_check CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT action_run_action_type_check CHECK (action_type IN ('SEND_NOTIFICATION', 'CREATE_TASK', 'FORWARD_DATA', 'ESCALATE')),
    CONSTRAINT action_run_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT action_run_target_check CHECK (target IN ('PATIENT', 'ASSIGNED_WORKER', 'SUPERVISOR', 'FACILITY'))
);

CREATE INDEX idx_action_run_action_definition ON action_run (action_definition_id);
CREATE INDEX idx_action_run_patient ON action_run (patient_id);
CREATE INDEX idx_action_run_protocol_instance ON action_run (protocol_instance_id);
CREATE INDEX idx_action_run_step_instance ON action_run (step_instance_id);
CREATE INDEX idx_action_run_status ON action_run (status);
CREATE INDEX idx_action_run_severity ON action_run (severity);
CREATE INDEX idx_action_run_created_at ON action_run (created_at);
