CREATE TABLE flows (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL,
    gateway_id UUID NOT NULL,
    active_flow_version_id UUID,
    active_flow_version_status VARCHAR(100),
    flow_key VARCHAR(150) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    http_method VARCHAR(50) NOT NULL,
    path VARCHAR(2048) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_flows_flow_key UNIQUE (flow_key)
);

CREATE TABLE flow_versions (
    id UUID PRIMARY KEY,
    flow_id UUID NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(100) NOT NULL DEFAULT 'DRAFT',
    runtime VARCHAR(100) NOT NULL DEFAULT 'NODE',
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    adopted_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_flow_versions_flow_id_version UNIQUE (flow_id, version),
    CONSTRAINT uk_flow_versions_flow_id_id UNIQUE (flow_id, id),
    CONSTRAINT uk_flow_versions_id_status UNIQUE (id, status)
);

CREATE TABLE flow_steps (
    id UUID PRIMARY KEY,
    flow_version_id UUID NOT NULL,
    step_key VARCHAR(150) NOT NULL,
    component_type VARCHAR(100) NOT NULL,
    position INTEGER NOT NULL,
    component_id UUID NOT NULL,
    component_version_id UUID NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_flow_steps_version_step_key UNIQUE (flow_version_id, step_key),
    CONSTRAINT uk_flow_steps_version_position UNIQUE (flow_version_id, position)
);
