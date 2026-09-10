CREATE TABLE invocation_step_executions (
    id UUID PRIMARY KEY,
    invocation_id UUID NOT NULL,
    flow_id UUID NOT NULL,
    flow_version_id UUID NOT NULL,
    step_id UUID NOT NULL,
    position INTEGER NOT NULL,
    component_type VARCHAR(100) NOT NULL,
    component_id UUID NOT NULL,
    component_version_id UUID NOT NULL,
    status VARCHAR(100) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_invocation_step_executions_invocation_step_attempt UNIQUE (invocation_id, step_id, attempt)
);
