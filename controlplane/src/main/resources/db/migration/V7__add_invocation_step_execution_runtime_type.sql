ALTER TABLE invocation_step_executions
ADD COLUMN runtime_type VARCHAR(100) NOT NULL DEFAULT 'NODE';
