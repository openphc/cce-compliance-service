-- ==============================================================================
-- CCE Compliance Service — Performance Indexes
-- ==============================================================================
-- Flyway Migration: V3
-- Database: PostgreSQL 16
-- ==============================================================================

-- Composite index for optimized auto-skip query on step completion
-- Supports: findByProtocolInstanceIdAndRequiredBehaviorAndStateIn
CREATE INDEX idx_step_instance_protocol_behavior_state
    ON step_instance (protocol_instance_id, required_behavior, state)
    WHERE required_behavior = 'could' AND state IN ('PENDING', 'DUE', 'OVERDUE');

-- Composite index for protocol instance patient+status lookups
CREATE INDEX idx_protocol_instance_patient_status
    ON protocol_instance (patient_id, status);

-- Index for step instance action lookups (used in findActionableStep)
CREATE INDEX idx_step_instance_protocol_action_state
    ON step_instance (protocol_instance_id, action_id, state)
    WHERE state IN ('PENDING', 'DUE', 'OVERDUE');
