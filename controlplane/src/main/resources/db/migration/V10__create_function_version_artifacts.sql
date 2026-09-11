CREATE TABLE functions (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    function_key VARCHAR(150) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    runtime VARCHAR(100) NOT NULL DEFAULT 'NODE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_functions_function_key UNIQUE (function_key)
);

CREATE TABLE function_versions (
    id UUID PRIMARY KEY,
    function_id UUID NOT NULL REFERENCES functions(id) ON DELETE CASCADE ON UPDATE CASCADE,
    version INTEGER NOT NULL,
    runtime VARCHAR(100) NOT NULL DEFAULT 'NODE',
    artifact_object_key VARCHAR(2048),
    artifact_format VARCHAR(100),
    artifact_published_at TIMESTAMP WITH TIME ZONE,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_function_versions_function_id_version UNIQUE (function_id, version)
);
