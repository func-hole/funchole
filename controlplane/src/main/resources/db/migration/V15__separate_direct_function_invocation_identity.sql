ALTER TABLE invocations
ADD COLUMN function_id UUID,
ADD COLUMN function_key VARCHAR(255),
ADD COLUMN function_version_id UUID;
