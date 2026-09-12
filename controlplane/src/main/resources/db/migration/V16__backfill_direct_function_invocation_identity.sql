-- V15 added function_* identity columns but left flow_id/flow_key/flow_version_id
-- NOT NULL from V4, so a DIRECT_FUNCTION row can never actually have null Flow
-- identity as the new architecture requires. Relax that first, then backfill any
-- pre-V15 DIRECT_FUNCTION rows out of the old flow_* columns.
ALTER TABLE invocations
ALTER COLUMN flow_id DROP NOT NULL,
ALTER COLUMN flow_key DROP NOT NULL,
ALTER COLUMN flow_version_id DROP NOT NULL;

UPDATE invocations
SET function_id = flow_id,
    function_key = flow_key,
    function_version_id = flow_version_id,
    flow_id = NULL,
    flow_key = NULL,
    flow_version_id = NULL
WHERE kind = 'DIRECT_FUNCTION'
  AND flow_id IS NOT NULL;
