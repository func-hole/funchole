CREATE TABLE function_version_sources (
    function_version_id UUID PRIMARY KEY REFERENCES function_versions(id) ON DELETE CASCADE ON UPDATE CASCADE,
    runtime_type VARCHAR(100) NOT NULL,
    runtime_version VARCHAR(100),
    entrypoint VARCHAR(2048) NOT NULL,
    relative_paths JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
