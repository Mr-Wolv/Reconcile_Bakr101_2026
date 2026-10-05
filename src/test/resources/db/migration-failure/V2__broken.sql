-- Deliberately broken, for E2E-MIG-01.
--
-- The order matters and is the point of the fixture: the table is created FIRST and the failure
-- comes second. A migration whose very first statement fails proves only that nothing started. One
-- that succeeds and then breaks proves the whole migration is atomic — which is the property an
-- operator is relying on when they roll forward after a failure, and the reason the previous
-- version is still intact rather than half-upgraded.
CREATE TABLE migration_probe_v2 (
    id text PRIMARY KEY
);

SELECT * FROM this_table_does_not_exist;