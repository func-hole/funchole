ALTER TABLE invocations
ADD COLUMN kind VARCHAR(50);

UPDATE invocations SET kind = 'FLOW' WHERE kind IS NULL;

ALTER TABLE invocations
ALTER COLUMN kind SET NOT NULL;
