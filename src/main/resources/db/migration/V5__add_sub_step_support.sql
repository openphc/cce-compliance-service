-- V5: Sub-step support.
-- 1. Add parent_step_id to step_instance for parent-child step relationships.
-- 2. Add parent_action_id to step_instance to reference the parent action in PlanDefinition.
-- 3. Add grouping_behavior and selection_behavior to track group completion semantics.

ALTER TABLE step_instance ADD COLUMN parent_step_id UUID REFERENCES step_instance(id);
ALTER TABLE step_instance ADD COLUMN parent_action_id VARCHAR(255);

CREATE INDEX idx_step_instance_parent_step_id ON step_instance(parent_step_id);
