-- V2 — financial invariants the baseline did not actually enforce, and a terminal event state.
--
-- Three independent defects, one file, because they are all "the schema promised something the
-- baseline did not deliver" and they must all be verifiable in one clean-database run.
--
--   1. L1/L3 were only half-enforced. tg_txn_balances fires AFTER INSERT ON ledger_entries, so a
--      header committed on its own was a POSTED ledger transaction with no entries at all.
--   2. settlement_records had no validity. A record whose totals contradicted its own lines, or
--      whose currency contradicted the payments it covered, was ingested and settled anyway.
--   3. provider_events used FAILED for both "retry when you can" and "never again", so a terminal
--      event was immediately re-claimable and burned the whole retry budget.
--
-- Every change below is additive. No column is dropped, no table is rebuilt, and no existing row is
-- rewritten: a database already carrying V1 data migrates forward in place.

-- ---------------------------------------------------------------------------------------------
-- 1. L1 / L3 at COMMIT, from the header as well as from the entries.
-- ---------------------------------------------------------------------------------------------
--
-- The baseline enforces balance from the entry side only. That is correct as far as it goes -- a
-- transaction is only unbalanced if its entries are -- but it is silent about the one case that
-- matters most: no entries at all. `INSERT INTO ledger_transactions (...) VALUES (...)` writes a
-- header in state POSTED with an empty entry set, no entry-side trigger fires, and the commit
-- succeeds. That is a financial record claiming to be posted while representing no money movement,
-- and the application never sees it because it was written without the application.
--
-- The fix is the same mechanism used on the entry side, applied to the header: a DEFERRABLE
-- INITIALLY DEFERRED constraint trigger, so it runs at COMMIT when the transaction is complete and
-- can ask the question "does this transaction have at least two entries, and do they balance?"
-- without racing the application that is still inserting them.
--
-- Why both triggers and not one. A single header-side trigger would already cover every path,
-- because it can see all of the entries at commit time. The entry-side trigger is kept because it
-- is the one that reports a genuinely unbalanced transaction, and it reports it against the row
-- that caused it. Two enforcement points for one invariant is normally duplication; here the
-- header trigger's job is presence (does this transaction have entries at all?) and the entry
-- trigger's job is arithmetic (do they balance?), which are genuinely different questions and only
-- one of which can be answered from the header alone in a useful way.
--
-- Note on the check itself: `n < 2 OR d <> c` also enforces L3's "at least one debit and at least
-- one credit", because two entries summing to equal totals cannot both be debits or both credits
-- without one of them being zero, and amount_minor > 0 is a column CHECK. A transaction with a
-- single DEBIT and a single CREDIT of the same amount is the minimum legal shape; a single entry is
-- rejected by the count, and a zero-amount entry cannot exist.
CREATE OR REPLACE FUNCTION fn_txn_header_balances() RETURNS TRIGGER AS $$
DECLARE d BIGINT; c BIGINT; n BIGINT; mixed BOOLEAN;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*),
           COUNT(DISTINCT currency) > 1
      INTO d, c, n, mixed
      FROM ledger_entries
     WHERE transaction_id = NEW.id;

    IF mixed THEN
        RAISE EXCEPTION 'ledger transaction % mixes currencies', NEW.id
            USING ERRCODE = '23514';
    END IF;
    IF n < 2 THEN
        -- Raised separately from the arithmetic case on purpose. The previous audit found this
        -- hole by inserting a header and reading "POSTED, entries []" back out of the API; an
        -- operator who sees this message should not have to work out that zero entries and an
        -- unbalanced transaction are the same symptom.
        RAISE EXCEPTION 'ledger transaction % is POSTED with % entries; at least 2 are required',
              NEW.id, n
            USING ERRCODE = '23514';
    END IF;
    IF d <> c THEN
        RAISE EXCEPTION 'ledger transaction % does not balance: debits=% credits=% entries=%',
              NEW.id, d, c, n
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_txn_header_balances
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_txn_header_balances();

-- ---------------------------------------------------------------------------------------------
-- 2. Settlement validity.
-- ---------------------------------------------------------------------------------------------
--
-- A settlement record is a statement from the provider about money it has paid. Three things about
-- it can be false while every individual field still looks reasonable:
--
--   * its totals can disagree with its own lines (record net 9,600 over a line of net 9,700);
--   * its currency can disagree with the payments it covers (a USD record settling an EGP payment);
--   * its lines can disagree among themselves (the same transaction listed twice, or a line whose
--     net exceeds its gross).
--
-- None of these is caught by a column CHECK, because each is a *relationship* between rows. The
-- baseline recorded the record as sent and moved on, which is how a signed, authenticated but
-- internally inconsistent provider statement settled real money and then reconciled as MATCHED.
--
-- validity records the outcome of that judgement durably. An INVALID record is kept in full -- the
-- provider's own figures, verbatim -- because it is the evidence an operator needs; what it is not
-- allowed to do is settle a payment, post to the ledger, or certify a reconciliation as matched.
--
-- The check constraint states the part that is cheap to check per row, and
-- SettlementRecordValidator (Java) states the parts that need to join lines and payments. The
-- constraint exists so that a row written by anything other than the validator still cannot claim
-- to be VALID while carrying a reason, or be VALID with no reason at all.
ALTER TABLE settlement_records
    ADD COLUMN validity        VARCHAR(8) NOT NULL DEFAULT 'VALID',
    ADD COLUMN validity_reason VARCHAR(500);

ALTER TABLE settlement_records
    ADD CONSTRAINT ck_settlement_validity
        CHECK (validity IN ('VALID','INVALID'));

-- ck_settlement_split is relaxed, deliberately and in one direction only.
--
-- It used to require every stored settlement record to satisfy gross = net + fee. That is the right
-- rule for a record we are going to ACT on and the wrong rule for a row in an evidence store: a
-- provider whose own arithmetic is broken must still be recordable, verbatim, or the system
-- destroys the very document an operator needs in order to complain to them. So the split is now
-- required of a VALID record and waived for an INVALID one.
--
-- The relaxation is one-directional on purpose. An INVALID record can carry inconsistent figures;
-- a VALID record cannot, so a bug that marked a broken record VALID still cannot store it.
ALTER TABLE settlement_records
    DROP CONSTRAINT ck_settlement_split;
ALTER TABLE settlement_records
    ADD CONSTRAINT ck_settlement_split
        CHECK (validity = 'INVALID'
               OR gross_amount_minor = net_amount_minor + fee_amount_minor);

-- A VALID record carries no complaint; an INVALID one must say why. An operator staring at a
-- settlement that refused to move money needs the reason to be in the row, not in a log line that
-- has already rotated away.
ALTER TABLE settlement_records
    ADD CONSTRAINT ck_settlement_validity_reason
        CHECK ((validity = 'VALID' AND validity_reason IS NULL)
            OR (validity = 'INVALID' AND validity_reason IS NOT NULL));

-- Backing index for "every settlement that refused to settle anything", which is the query an
-- operator runs first when the platform and the PSP disagree about money.
CREATE INDEX ix_settlement_validity ON settlement_records (provider, validity);

-- ---------------------------------------------------------------------------------------------
-- 3. A terminal event state, distinct from a retryable failure.
-- ---------------------------------------------------------------------------------------------
--
-- `fail()` wrote FAILED with next_attempt_at = now() for BOTH a transient fault and a permanent
-- one, and the claim query selected status IN ('PENDING','FAILED'). So a permanently broken event
-- -- an unknown event type, an unparseable stored payload, a settlement whose own totals contradict
-- -- its lines -- was immediately re-claimable and consumed the full eight-attempt budget to reach
-- -- a conclusion it had already reached on attempt one. Eight attempts, eight backoffs, and five
-- minutes of delay behind it for every poison event.
--
-- DEAD is the missing state: terminal, never claimable, and still on GET /provider/events/failed
-- so an operator can see it. FAILED keeps its original meaning of "waiting for its next attempt".
--
-- The CHECK is replaced rather than extended because V1 declared it inline and unnamed, so its
-- generated name is a PostgreSQL convention rather than something this project chose. The DO block
-- finds it by definition so the migration does not depend on the naming convention holding.
DO $$
DECLARE old_check TEXT;
BEGIN
    SELECT conname INTO old_check
      FROM pg_constraint
     WHERE conrelid = 'provider_events'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%status%PENDING%PROCESSING%PROCESSED%NO_EFFECT%FAILED%';

    IF old_check IS NULL THEN
        RAISE EXCEPTION 'provider_events status check not found; V2 expects the V1 shape';
    END IF;

    EXECUTE format('ALTER TABLE provider_events DROP CONSTRAINT %I', old_check);
END $$;

ALTER TABLE provider_events
    ADD CONSTRAINT ck_provider_event_status
        CHECK (status IN ('PENDING','PROCESSING','PROCESSED','NO_EFFECT','FAILED','DEAD'));

-- The claimable index now says what it means. Before this, a terminal event was indistinguishable
-- from one merely waiting for its backoff, so the index served both and could exclude neither.
DROP INDEX ix_events_claimable;
CREATE INDEX ix_events_claimable ON provider_events (next_attempt_at)
    WHERE status IN ('PENDING','FAILED');

-- The terminal backlog is its own access path: GET /provider/events/failed reads it, and an
-- operator asking "is anything permanently broken?" should not scan a queue full of events that
-- are merely waiting.
CREATE INDEX ix_events_dead ON provider_events (next_attempt_at)
    WHERE status = 'DEAD';

-- ============================================================================
-- The outcome vocabulary gained a member, and the database had to be told.
-- ============================================================================
--
-- Found by audit-probe/vv-remediation.mjs against a running container, not by the test suite.
--
-- F-01/F-02 added ReconciliationOutcome.SETTLEMENT_RECORD_INVALID, and V1 pins `outcome` to a
-- CHECK constraint listing every outcome that existed when it was written. The enum grew; the
-- constraint did not. So the first batch that actually classified an invalid settlement - the
-- exact event the fix exists to catch - was refused at INSERT:
--
--   ERROR: new row for relation "reconciliation_results" violates check constraint
--          "reconciliation_results_outcome_check"
--
-- and ReconciliationWorker marked the whole batch FAILED with processed_subjects = 0. Every
-- payment in that window went unreconciled because one of them had a bad settlement record: the
-- failure mode was "the engine stops reconciling precisely when it finds something".
--
-- Why the suite missed it: the unit tests exercise the classifier, which is pure, and the
-- integration tests that reach a settlement either do not insert a result row for it or assert the
-- Java enum rather than the persisted value. A constraint is only covered by a test that writes
-- the constrained value through the constraint.
--
-- The constraint is dropped and recreated by name rather than altered, because its definition is
-- inline in V1 with an automatically generated name that is not worth depending on; this block
-- looks the name up the way the provider_events block above does, and fails loudly if V1's shape
-- is not what it expects.
DO $$
DECLARE
    old_check TEXT;
BEGIN
    SELECT conname INTO old_check
      FROM pg_constraint
     WHERE conrelid = 'reconciliation_results'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%MATCHED_NOT_SETTLED%'
       AND pg_get_constraintdef(oid) NOT LIKE '%SETTLEMENT_RECORD_INVALID%'
     LIMIT 1;

    IF old_check IS NULL THEN
        RAISE EXCEPTION 'reconciliation_results outcome check not found; V2 expects the V1 shape';
    END IF;

    EXECUTE format('ALTER TABLE reconciliation_results DROP CONSTRAINT %I', old_check);
END $$;

ALTER TABLE reconciliation_results
    ADD CONSTRAINT reconciliation_results_outcome_check
        CHECK (outcome IN ('MATCHED','MATCHED_NOT_SETTLED','AMOUNT_MISMATCH','CURRENCY_MISMATCH',
                           'STATUS_MISMATCH','REFERENCE_MISMATCH','SETTLEMENT_DATE_MISMATCH',
                           'MISSING_ON_PROVIDER','MISSING_INTERNAL',
                           'DUPLICATE_PROVIDER_RECORD','AMBIGUOUS_MATCH',
                           'SETTLEMENT_RECORD_INVALID'));

-- The audit trail for the new outcome. Without this the table is fine but the new verdict is
-- invisible to any query that asks "how much money did we refuse, and why".
CREATE INDEX ix_results_invalid_settlement ON reconciliation_results (batch_id)
    WHERE outcome = 'SETTLEMENT_RECORD_INVALID';