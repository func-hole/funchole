ALTER TABLE function_versions
ADD COLUMN artifact_sha256 VARCHAR(64),
ADD COLUMN artifact_size_bytes BIGINT;
