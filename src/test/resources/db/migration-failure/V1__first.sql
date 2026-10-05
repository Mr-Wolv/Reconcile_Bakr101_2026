-- Fixture for E2E-MIG-01. Applies cleanly, so the failing migration that follows has a real
-- previous version to fall back to — otherwise "the schema stayed where it was" would be a
-- statement about an empty database rather than about a rollback.
CREATE TABLE migration_probe (
    id         text PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);