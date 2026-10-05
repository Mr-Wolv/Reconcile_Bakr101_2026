# 05 — Database Schema

Target: PostgreSQL 18.6. The baseline migration is
`src/main/resources/db/migration/V1__baseline.sql` and
`V2__financial_invariants_and_terminal_events.sql`, and they must run to completion on an empty
database with no manual steps. Every object below is created by that one file; nothing is created
by `ddl-auto`.

Conventions: `TIMESTAMPTZ` in UTC; money as `BIGINT` minor units plus an explicit currency column;
ULID text keys prefixed by entity type; `JSONB` for structured payloads that are read as
documents rather than queried relationally; no `ON DELETE CASCADE` on financial history.

### Validation status

This schema is not a sketch. On 2026-10-04 the DDL below was extracted verbatim from this
document and executed against a live **PostgreSQL 18** instance (`ON_ERROR_STOP=1`),
and the enforcement claims in [ADR-0004](../adr/0004-database-enforced-invariants.md) were
exercised rather than assumed. Results:

| Claim | Result |
| --- | --- |
| The whole baseline applies cleanly (`ON_ERROR_STOP=1`) | **pass** |
| The baseline file contains 68 top-level `CREATE`/`ALTER` object declarations (23 tables, 32 indexes, 9 triggers, 3 functions, 1 `ALTER`) | **pass** |
| **Full cycle capture → settlement → payout** unwinds the ledger to `PSP_CLEARING = 0`, `MERCHANT_PAYABLE = 0`, `PLATFORM_CASH = 3.00`, `PLATFORM_FEE_REVENUE = 3.00` | **pass** |
| L7 verifier returns zero rows on that consistent state, and names the account after corruption is injected | **pass** |
| L1 unbalanced transaction refused at COMMIT by the deferred trigger | **pass** |
| L2 mixed-currency transaction refused | **pass** |
| L4/L5 `UPDATE`/`DELETE` refused on `ledger_transactions`, `ledger_entries`, `payment_state_history` | **pass** |
| L6 second reversal refused; **reversal chain accepted** | **pass** |
| L8 a payout larger than `PLATFORM_CASH` refused; a negative liability accepted | **pass** |
| L10 duplicate posting refused by `ux_ledger_source` | **pass** |
| L11 a payment cannot be paid out twice, refused by `ux_payout_line_payment` | **pass** |
| `ck_payout_executed`, `ck_payment_split`, `ck_case_resolution`, `ux_provider_txn`, `ux_case_open_subject_reason` fire | **pass** |

Three defects were found by running it, and fixed here rather than shipped:

1. The original `ux_ledger_source` covered reversals as well, which made a reversal chain
   impossible while the specification promised one. It is now partial on `type <> 'REVERSAL'`.
2. `account_balances` had no rows, so the L8 trigger could be side-stepped by updating a
   non-existent row. The projection is now keyed by `(account_id, currency)` and seeded for
   every account and supported currency.
3. The payout additions were first written with the ledger FK checked before the referenced
   transaction existed, which made the double-payout test pass for the wrong reason. The
   ordering in the migration — ledger posting first, then the payout record, then its lines — is
   now what the test actually exercises.

`I-MIG-01` through `I-LED-06` and `I-PAYOUT-01` through `I-PAYOUT-07` will re-prove all of this in
CI on every push. This table records what was verified once, not a substitute for those tests.

---

## 1. Reference data

```sql
CREATE TABLE currency_units (
    code        CHAR(3) PRIMARY KEY,
    exponent    SMALLINT  NOT NULL CHECK (exponent BETWEEN 0 AND 4),
    display_name TEXT     NOT NULL,
    active      BOOLEAN   NOT NULL DEFAULT TRUE
);

INSERT INTO currency_units (code, exponent, display_name) VALUES
  ('EGP', 2, 'Egyptian Pound'),
  ('USD', 2, 'US Dollar'),
  ('EUR', 2, 'Euro'),
  ('GBP', 2, 'Pound Sterling');
```

```sql
CREATE TABLE fee_policies (
    id           TEXT PRIMARY KEY,
    code         VARCHAR(32) NOT NULL UNIQUE,
    bps          INTEGER     NOT NULL CHECK (bps BETWEEN 0 AND 10000),
    currency     CHAR(3)     NOT NULL REFERENCES currency_units (code),
    effective_from TIMESTAMPTZ NOT NULL,
    active       BOOLEAN     NOT NULL DEFAULT TRUE
);

-- v1 default: 3.00% of gross, so a 100.00 EGP payment yields fee 3.00 / net 97.00.
INSERT INTO fee_policies (id, code, bps, currency, effective_from)
VALUES ('fp_default', 'DEFAULT', 300, 'EGP', '2026-01-01T00:00:00Z');
```

## 2. Chart of accounts and balances

```sql
CREATE TABLE ledger_accounts (
    id           TEXT PRIMARY KEY,
    code         VARCHAR(32) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    account_type VARCHAR(16) NOT NULL
                 CHECK (account_type IN ('ASSET','LIABILITY','REVENUE','EXPENSE')),
    normal_side  VARCHAR(6)  NOT NULL
                 CHECK (normal_side IN ('DEBIT','CREDIT')),
    state        VARCHAR(8)  NOT NULL DEFAULT 'ACTIVE'
                 CHECK (state IN ('ACTIVE','CLOSED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keyed by (account, currency), not account alone: an account may hold several currencies, and a
-- single-currency ledger transaction (L2) simply posts into one of them.
CREATE TABLE account_balances (
    account_id    TEXT NOT NULL REFERENCES ledger_accounts (id),
    currency      CHAR(3) NOT NULL REFERENCES currency_units (code),
    -- balance in the account's NORMAL direction: positive always means "more of this account"
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    entry_count   BIGINT      NOT NULL DEFAULT 0 CHECK (entry_count >= 0),
    last_entry_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (account_id, currency)
);

-- Enforces ledger invariant L8 at the storage layer, not only in Java. A plain CHECK cannot
-- express it, because the rule depends on ledger_accounts.account_type rather than on columns of
-- account_balances, so the constraint is attached by a trigger that reads the account type.
CREATE OR REPLACE FUNCTION fn_asset_non_negative() RETURNS TRIGGER AS $$
DECLARE a_type VARCHAR(16);
BEGIN
    SELECT account_type INTO a_type FROM ledger_accounts WHERE id = NEW.account_id;
    IF a_type IN ('ASSET') AND NEW.balance_minor < 0 THEN
        RAISE EXCEPTION 'asset account % would go negative: % minor units',
              NEW.account_id, NEW.balance_minor
              USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER tg_asset_non_negative
    BEFORE INSERT OR UPDATE ON account_balances
    FOR EACH ROW EXECUTE FUNCTION fn_asset_non_negative();
```

Seeded chart (`PSP_FEE_EXPENSE` is defined but never posted to in v1 — see
[01 §2](01-money-and-ledger.md)):

```sql
INSERT INTO ledger_accounts (id, code, name, account_type, normal_side) VALUES
 ('acct_psp_clearing',   'PSP_CLEARING',          'Receivable from the PSP',        'ASSET',     'DEBIT'),
 ('acct_platform_cash',  'PLATFORM_CASH',         'Platform cash',                  'ASSET',     'DEBIT'),
 ('acct_merchant_payable','MERCHANT_PAYABLE',     'Amount owed to merchants',       'LIABILITY', 'CREDIT'),
 ('acct_platform_fee_rev','PLATFORM_FEE_REVENUE',  'Platform fee revenue',           'REVENUE',   'CREDIT'),
 ('acct_psp_fee_exp',    'PSP_FEE_EXPENSE',       'Fees charged by the PSP',        'EXPENSE',   'DEBIT');

-- Seed one balance row per account per supported currency, all zero. Seeding eagerly means the
-- projection row always exists, so the L8 trigger can never be bypassed by the absence of a row
-- (an UPDATE against a missing row updates nothing and would silently succeed).
INSERT INTO account_balances (account_id, currency)
SELECT a.id, c.code
  FROM ledger_accounts a
 CROSS JOIN currency_units c
 WHERE c.active;
```

## 3. Ledger

```sql
CREATE TABLE ledger_transactions (
    id                TEXT PRIMARY KEY,
    type              VARCHAR(32) NOT NULL
                      CHECK (type IN ('PAYMENT_CAPTURE','REVERSAL',
                                      'PAYMENT_REFUND_SETTLED','SETTLEMENT_RECEIVED',
                                      'MERCHANT_PAYOUT','PSP_ADJUSTMENT')),
    state             VARCHAR(8)  NOT NULL DEFAULT 'POSTED'
                      CHECK (state = 'POSTED'),          -- decision D1: no PENDING exists
    currency          CHAR(3)     NOT NULL REFERENCES currency_units (code),
    -- L10: the database-level double-post guard, enforced by ux_ledger_source below.
    source_type       VARCHAR(32) NOT NULL
                      CHECK (source_type IN ('PAYMENT','SETTLEMENT_RECORD','PAYOUT',
                                             'RECONCILIATION_CASE','SYSTEM')),
    source_id         TEXT        NOT NULL,
    description       VARCHAR(280) NOT NULL,
    reversal_of_transaction_id TEXT REFERENCES ledger_transactions (id),
    reversal_reason   VARCHAR(64),
    idempotency_key   VARCHAR(255),
    request_id        VARCHAR(64),
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_actor  VARCHAR(64) NOT NULL,

    CONSTRAINT ux_ledger_reversal    UNIQUE (reversal_of_transaction_id),
    CONSTRAINT ck_ledger_reversal_ok CHECK (
        (type = 'REVERSAL'     AND reversal_of_transaction_id IS NOT NULL AND reversal_reason IS NOT NULL)
     OR (type <> 'REVERSAL'    AND reversal_of_transaction_id IS NULL)
    ),
    -- A reversal transaction posts to no sources and settles nothing.
    CONSTRAINT ck_ledger_reversal_self CHECK (reversal_of_transaction_id IS DISTINCT FROM id)
);

-- L10: the database-level double-post guard.
--
-- Deliberately EXCLUDES reversals. A reversal is identified by the transaction it reverses
-- (ux_ledger_reversal, L6), and a chain of reversals is legitimate accounting — a reversal may
-- itself be reversed. Including REVERSAL here would let only one reversal exist per source
-- forever, which would silently forbid the chain.
CREATE UNIQUE INDEX ux_ledger_source
    ON ledger_transactions (source_type, source_id, type)
    WHERE type <> 'REVERSAL';

CREATE TABLE ledger_entries (
    id              TEXT PRIMARY KEY,
    transaction_id  TEXT NOT NULL REFERENCES ledger_transactions (id),
    account_id      TEXT NOT NULL REFERENCES ledger_accounts (id),
    -- direction carries the sign; amount_minor is always strictly positive (invariant L3)
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3)     NOT NULL REFERENCES currency_units (code),
    account_code    VARCHAR(32) NOT NULL,
    line_no         SMALLINT    NOT NULL CHECK (line_no >= 1),
    posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ux_entry_txn_line UNIQUE (transaction_id, line_no)
);

-- serves: statement of account for one account, newest first
CREATE INDEX ix_entries_account_posted ON ledger_entries (account_id, posted_at DESC, id);
-- serves: the complete ledger story of one payment  (01 §8)
CREATE INDEX ix_entries_txn           ON ledger_entries (transaction_id);
-- serves: ledger invariant verification
CREATE INDEX ix_entries_account        ON ledger_entries (account_id);
```

### Immutability and the balance invariant, in the database

```sql
-- Invariant L4 / L5: posted ledger history is immutable. Not an application convention —
-- the database refuses, so no bug or psql session can rewrite financial history.
CREATE OR REPLACE FUNCTION fn_deny_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();

CREATE TRIGGER tg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();

-- Invariant L1 enforced at COMMIT time, not at insert time: a transaction is only ever
-- evaluated once all of its entries exist, so it can never be observed unbalanced.
CREATE OR REPLACE FUNCTION fn_txn_balances() RETURNS TRIGGER AS $$
DECLARE d BIGINT; c BIGINT; n BIGINT; mixed BOOLEAN;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*),
           COUNT(DISTINCT currency) > 1
      INTO d, c, n, mixed
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF mixed THEN
        RAISE EXCEPTION 'transaction % mixes currencies', NEW.transaction_id
            USING ERRCODE = '23514';
    END IF;
    IF n < 2 OR d <> c THEN
        RAISE EXCEPTION 'transaction % does not balance: debits=% credits=% entries=%',
              NEW.transaction_id, d, c, n
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_txn_balances
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_txn_balances();

-- V2 adds the same mechanism on the HEADER. Without it, a header committed with no entries at all
-- was a POSTED ledger transaction representing no money movement, because no entry insert ever
-- fired the trigger above. Both are needed: the entry trigger answers "do they balance", and only
-- the header trigger can answer "are there any". See docs/adr/0004.
CREATE CONSTRAINT TRIGGER tg_txn_header_balances
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_txn_header_balances();
```

This trigger is the single most valuable object in the schema. The Java validator gives a good
error message; this one guarantees that **no** transaction can ever commit unbalanced, whatever
writes it.

## 4. Payments

```sql
CREATE TABLE payments (
    id                     TEXT PRIMARY KEY,
    merchant_reference     VARCHAR(64) NOT NULL,
    description            VARCHAR(280),
    amount_minor           BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency               CHAR(3)     NOT NULL REFERENCES currency_units (code),
    platform_fee_minor     BIGINT      NOT NULL CHECK (platform_fee_minor >= 0),
    net_amount_minor       BIGINT      NOT NULL CHECK (net_amount_minor >= 0),
    fee_policy_id          TEXT        NOT NULL REFERENCES fee_policies (id),
    state                  VARCHAR(16) NOT NULL
                           CHECK (state IN ('CREATED','AUTHORIZED','CAPTURED',
                                            'SETTLED','FAILED','REFUNDED')),
    failure_code           VARCHAR(32),
    failure_reason         VARCHAR(280),
    provider               VARCHAR(32) NOT NULL,
    provider_transaction_id VARCHAR(64),
    settlement_record_id   TEXT,
    captured_at            TIMESTAMPTZ,   -- set on T3; the reconciliation scope index key
    settled_at             TIMESTAMPTZ,   -- set on T5
    paid_out_at            TIMESTAMPTZ,   -- set when a payout line covering this payment executes
    idempotency_key        VARCHAR(255),
    expires_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,

    -- the fee split is frozen at creation: gross = net + fee, always, exactly
    CONSTRAINT ck_payment_split CHECK (amount_minor = net_amount_minor + platform_fee_minor),
    -- a FAILED payment always explains itself; other states carry no failure data
    CONSTRAINT ck_payment_failure CHECK (
        (state = 'FAILED' AND failure_code IS NOT NULL) OR
        (state <> 'FAILED' AND failure_code IS NULL)
    )
);

CREATE UNIQUE INDEX ux_payments_provider_txn
    ON payments (provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

-- serves the reconciliation scope query: captured payments inside a half-open window
CREATE INDEX ix_payments_captured ON payments (captured_at, id) WHERE state <> 'CREATED';
-- serves GET /api/v1/payments listings
CREATE INDEX ix_payments_state_created ON payments (state, created_at DESC, id);
```

The link between a payment and its postings. It is placed after `payments` because PostgreSQL
cannot resolve a forward reference in `REFERENCES` — a migration must create tables in dependency
order, which is why this file is ordered by dependency rather than by topic.

```sql
CREATE TABLE payment_ledger_transactions (
    payment_id            TEXT NOT NULL REFERENCES payments (id),
    ledger_transaction_id TEXT NOT NULL REFERENCES ledger_transactions (id),
    role                  VARCHAR(16) NOT NULL
                          CHECK (role IN ('CAPTURE','REFUND','SETTLEMENT','ADJUSTMENT','REVERSAL')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, ledger_transaction_id)
);

-- serves: reverse lookup from a posting to the payment it belongs to
CREATE INDEX ix_payment_ledger_txn ON payment_ledger_transactions (ledger_transaction_id);
```

```sql
CREATE TABLE payment_state_history (
    id            TEXT PRIMARY KEY,
    payment_id    TEXT NOT NULL REFERENCES payments (id),
    from_state    VARCHAR(16),
    to_state      VARCHAR(16) NOT NULL,
    trigger_type  VARCHAR(16) NOT NULL
                  CHECK (trigger_type IN ('API','WEBHOOK','SETTLEMENT','SYSTEM')),
    trigger_ref   VARCHAR(128),
    reason        VARCHAR(280),
    actor_type    VARCHAR(16) NOT NULL,
    actor_id      VARCHAR(64),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_payment_history ON payment_state_history (payment_id, occurred_at, id);

CREATE TRIGGER tg_payment_state_history_immutable
    BEFORE UPDATE OR DELETE ON payment_state_history
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();
```

## 5. Idempotency

```sql
CREATE TABLE idempotency_records (
    id              TEXT PRIMARY KEY,
    endpoint        VARCHAR(128) NOT NULL,
    idem_key        VARCHAR(255) NOT NULL,
    fingerprint     CHAR(64)     NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    response_status SMALLINT,
    response_body   JSONB,
    resource_type   VARCHAR(32),
    resource_id     TEXT,
    request_id      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ux_idem_endpoint_key UNIQUE (endpoint, idem_key),
    CONSTRAINT ck_idem_completed   CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL AND completed_at IS NULL)
     OR (status = 'COMPLETED'   AND response_status IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_idem_expiry ON idempotency_records (expires_at);
```

## 6. Provider events, transactions, settlements, payouts

```sql
-- Decision D4: every delivery attempt, including duplicates and rejects. Append-only.
CREATE TABLE provider_event_deliveries (
    id              TEXT PRIMARY KEY,
    provider        VARCHAR(32) NOT NULL,
    event_id        VARCHAR(128) NOT NULL,
    outcome         VARCHAR(32) NOT NULL
                    CHECK (outcome IN ('ACCEPTED','DUPLICATE','REJECTED_SIGNATURE',
                                       'REJECTED_TIMESTAMP','REJECTED_MALFORMED','TOO_LARGE')),
    http_status     SMALLINT    NOT NULL,
    signature_valid BOOLEAN     NOT NULL,
    reason          VARCHAR(280),
    request_id      VARCHAR(64),
    event_type      VARCHAR(48),
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_deliveries_event ON provider_event_deliveries (provider, event_id, received_at);
CREATE INDEX ix_deliveries_time ON provider_event_deliveries (received_at DESC);

CREATE TRIGGER tg_deliveries_immutable
    BEFORE UPDATE OR DELETE ON provider_event_deliveries
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();

-- The deduplicated canonical event. One row per provider event id, whatever the delivery count.
CREATE TABLE provider_events (
    id                       TEXT PRIMARY KEY,
    provider                 VARCHAR(32)  NOT NULL,
    event_id                 VARCHAR(128) NOT NULL,
    event_type               VARCHAR(48)  NOT NULL,
    provider_occurred_at     TIMESTAMPTZ  NOT NULL,
    provider_occurred_offset VARCHAR(16),
    payload                  JSONB        NOT NULL,
    payload_hash             CHAR(64)     NOT NULL,
    status                   VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','PROCESSING','PROCESSED','NO_EFFECT','FAILED')),
    attempts                 SMALLINT     NOT NULL DEFAULT 0,
    -- Defect 22. Budget for "not applicable yet", tracked apart from `attempts` so that an
    -- event which merely overtook its own capture does not spend the retry budget that
    -- exists for genuine faults and then die holding a settlement the provider really paid.
    deferred_attempts        SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at                TIMESTAMPTZ,
    locked_by                VARCHAR(64),
    last_error               VARCHAR(500),
    processed_at             TIMESTAMPTZ,
    duplicate_deliveries     INTEGER      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ux_provider_event UNIQUE (provider, event_id)
);

-- worker claim query
CREATE INDEX ix_events_claimable ON provider_events (next_attempt_at)
    WHERE status IN ('PENDING','FAILED');

-- V2 adds a third terminal state and its own access path. DEAD means retrying cannot change the
-- answer, so it is outside the claim query entirely; FAILED still means "waiting for its next
-- attempt", which is why the two must not share a status. See docs/spec/03 §B4.
CREATE INDEX ix_events_dead ON provider_events (next_attempt_at)
    WHERE status = 'DEAD';
CREATE INDEX ix_events_stale     ON provider_events (locked_at)
    WHERE status = 'PROCESSING';
CREATE INDEX ix_events_payload   ON provider_events USING GIN (payload);

CREATE TABLE provider_transactions (
    id                       TEXT PRIMARY KEY,
    provider                 VARCHAR(32)  NOT NULL,
    provider_transaction_id  VARCHAR(64)  NOT NULL,
    payment_id               TEXT REFERENCES payments (id),
    merchant_reference       VARCHAR(64)  NOT NULL,
    gross_amount_minor       BIGINT       NOT NULL CHECK (gross_amount_minor >= 0),
    fee_amount_minor         BIGINT       NOT NULL DEFAULT 0 CHECK (fee_amount_minor >= 0),
    net_amount_minor         BIGINT       NOT NULL CHECK (net_amount_minor >= 0),
    currency                 CHAR(3)      NOT NULL REFERENCES currency_units (code),
    provider_status          VARCHAR(24)  NOT NULL
                             CHECK (provider_status IN ('CREATED','AUTHORIZED','CAPTURED',
                                                        'SETTLED','REFUNDED','FAILED')),
    source                   VARCHAR(8)   NOT NULL DEFAULT 'WEBHOOK'
                             CHECK (source IN ('WEBHOOK','PULL','MANUAL')),
    captured_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ux_provider_txn UNIQUE (provider, provider_transaction_id),
    CONSTRAINT ck_provider_split CHECK (gross_amount_minor = net_amount_minor + fee_amount_minor)
);

CREATE INDEX ix_provider_txn_payment ON provider_transactions (payment_id);
CREATE INDEX ix_provider_txn_captured ON provider_transactions (provider, captured_at, id);

CREATE TABLE settlement_records (
    id                 TEXT PRIMARY KEY,
    provider           VARCHAR(32) NOT NULL,
    provider_settlement_id VARCHAR(64) NOT NULL,
    gross_amount_minor BIGINT      NOT NULL CHECK (gross_amount_minor >= 0),
    fee_amount_minor   BIGINT      NOT NULL DEFAULT 0 CHECK (fee_amount_minor >= 0),
    net_amount_minor   BIGINT      NOT NULL CHECK (net_amount_minor >= 0),
    currency           CHAR(3)     NOT NULL REFERENCES currency_units (code),
    settlement_date    DATE        NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    source             VARCHAR(16) NOT NULL CHECK (source IN ('WEBHOOK','PULL','MANUAL')),
    ledger_transaction_id TEXT REFERENCES ledger_transactions (id),

    CONSTRAINT ux_settlement UNIQUE (provider, provider_settlement_id),
    CONSTRAINT ck_settlement_split
        CHECK (gross_amount_minor = net_amount_minor + fee_amount_minor)
);

CREATE TABLE settlement_record_lines (
    id                       TEXT PRIMARY KEY,
    settlement_record_id     TEXT NOT NULL REFERENCES settlement_records (id),
    provider_transaction_id  VARCHAR(64) NOT NULL,
    provider_transaction_ref TEXT REFERENCES provider_transactions (id),
    line_gross_minor         BIGINT      NOT NULL CHECK (line_gross_minor >= 0),
    line_net_minor           BIGINT      NOT NULL CHECK (line_net_minor >= 0),
    line_no                  SMALLINT    NOT NULL,

    CONSTRAINT ux_settlement_line UNIQUE (settlement_record_id, line_no)
);

CREATE INDEX ix_settlement_lines_txn
    ON settlement_record_lines (provider_transaction_id);

-- payments.settlement_record_id is declared without an inline REFERENCES clause because
-- settlement_records does not exist at that point in the migration. The constraint is added here,
-- once both tables do — the same dependency-ordering rule that places
-- payment_ledger_transactions after payments.
ALTER TABLE payments
    ADD CONSTRAINT fk_payments_settlement_record
    FOREIGN KEY (settlement_record_id) REFERENCES settlement_records (id);
```

### Settlement validity (V2)

A settlement record is a claim about money, and a claim can be false in ways no column `CHECK` can
see, because each of them is a relationship between rows. `V2` therefore adds the durable outcome
of that judgement rather than trying to forbid the broken document:

```sql
ALTER TABLE settlement_records
    ADD COLUMN validity        VARCHAR(8) NOT NULL DEFAULT 'VALID',
    ADD COLUMN validity_reason VARCHAR(500);

ALTER TABLE settlement_records
    ADD CONSTRAINT ck_settlement_validity
        CHECK (validity IN ('VALID','INVALID'));

ALTER TABLE settlement_records
    ADD CONSTRAINT ck_settlement_validity_reason
        CHECK ((validity = 'VALID'   AND validity_reason IS NULL)
            OR (validity = 'INVALID' AND validity_reason IS NOT NULL));
```

| Column | Notes |
| --- | --- |
| `validity` | `VALID` may settle payments and post cash. `INVALID` is stored, surfaced, and inert. |
| `validity_reason` | Why it was refused, in enough detail for an operator. Required on `INVALID`. |
| `ix_settlement_validity` | Serves "every settlement that refused to settle anything" — the first query an operator runs when the platform and the PSP disagree. |

### The outcome vocabulary is a schema contract, not a Java enum (V2)

`reconciliation_results.outcome` is pinned by a `CHECK` constraint enumerating every outcome. That
makes the enum a **persisted vocabulary**, and adding a member to it without changing the schema is
a breaking change that no unit test can see.

This is not hypothetical. `SETTLEMENT_RECORD_INVALID` was added to `ReconciliationOutcome` while
fixing F-01/F-02, and `V1`'s constraint was left alone. The first batch that classified an invalid
settlement was refused at `INSERT`, and `ReconciliationWorker` marked the **whole batch** `FAILED`
with `processed_subjects = 0` — every payment in the window went unreconciled because one of them
had a bad settlement record. Found by `audit-probe/vv-remediation.mjs` against a running container.

`V2` therefore replaces the constraint:

```sql
-- looked up by shape, not by the name V1 happened to generate, and it raises if V1 is not what
-- this expects rather than silently creating a second, weaker constraint
ALTER TABLE reconciliation_results DROP CONSTRAINT <v1's outcome check>;

ALTER TABLE reconciliation_results
    ADD CONSTRAINT reconciliation_results_outcome_check
        CHECK (outcome IN ('MATCHED','MATCHED_NOT_SETTLED','AMOUNT_MISMATCH','CURRENCY_MISMATCH',
                           'STATUS_MISMATCH','REFERENCE_MISMATCH','SETTLEMENT_DATE_MISMATCH',
                           'MISSING_ON_PROVIDER','MISSING_INTERNAL',
                           'DUPLICATE_PROVIDER_RECORD','AMBIGUOUS_MATCH',
                           'SETTLEMENT_RECORD_INVALID'));

CREATE INDEX ix_results_invalid_settlement ON reconciliation_results (batch_id)
    WHERE outcome = 'SETTLEMENT_RECORD_INVALID';
```

**The rule this encodes:** a `CHECK` constraint is only covered by a test that writes the
constrained value *through* the constraint. Asserting that an enum member exists proves nothing
about whether the database accepts it.
`ReconciliationIT.theNewOutcomeSatisfiesTheDatabaseConstraint` does the former.

**`ck_settlement_split` is relaxed in exactly one direction.** It now reads:

```sql
CHECK (validity = 'INVALID' OR gross_amount_minor = net_amount_minor + fee_amount_minor)
```

A provider whose statement does not add up must still be recordable verbatim — an operator needs
the document in order to complain about it and to diff against the corrected statement. A `VALID`
record may not carry inconsistent figures, so the relaxation cannot be used to smuggle a broken
record past the check: validity is decided by `SettlementRecordValidator`, not by the writer.

The invariants themselves (S1–S9) and the behaviour of an invalid record are specified in
[04 §2.1](04-reconciliation.md).

### Payouts (decision D9)

A payout is a record over the individual payments it settles, exactly as a settlement record is
over the provider transactions it settles. That shape needs no per-merchant sub-ledger: the
shared `MERCHANT_PAYABLE` account is debited by the sum of the line amounts, and each line names
the payment it discharges.

```sql
CREATE TABLE payout_records (
    id                   TEXT PRIMARY KEY,
    merchant_reference   VARCHAR(64) NOT NULL,
    currency             CHAR(3)     NOT NULL REFERENCES currency_units (code),
    total_net_minor      BIGINT      NOT NULL CHECK (total_net_minor > 0),
    line_count           INTEGER     NOT NULL CHECK (line_count > 0),
    status               VARCHAR(16) NOT NULL DEFAULT 'EXECUTED'
                         CHECK (status IN ('PLANNED','EXECUTED')),
    ledger_transaction_id TEXT REFERENCES ledger_transactions (id),
    executed_at          TIMESTAMPTZ,
    created_by_actor     VARCHAR(64) NOT NULL,
    request_id           VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- an EXECUTED payout must name the posting that moved the money
    CONSTRAINT ck_payout_executed CHECK (
        (status = 'EXECUTED' AND executed_at IS NOT NULL
                   AND ledger_transaction_id IS NOT NULL)
     OR (status = 'PLANNED')
    )
);

-- L11: a payment may be paid out at most once, ever. This is the structural guarantee behind
-- POST /payouts being idempotent under retry and under concurrent submission.
CREATE TABLE payout_lines (
    id               TEXT PRIMARY KEY,
    payout_record_id TEXT NOT NULL REFERENCES payout_records (id),
    payment_id       TEXT NOT NULL REFERENCES payments (id),
    line_net_minor   BIGINT NOT NULL CHECK (line_net_minor > 0),
    line_no          SMALLINT NOT NULL CHECK (line_no >= 1),

    CONSTRAINT ux_payout_line_payment UNIQUE (payment_id),
    CONSTRAINT ux_payout_line_no      UNIQUE (payout_record_id, line_no)
);

-- serves: a merchant's payout history
CREATE INDEX ix_payout_records_merchant ON payout_records (merchant_reference, created_at DESC);
```

## 7. Reconciliation

```sql
CREATE TABLE reconciliation_batches (
    id                TEXT PRIMARY KEY,
    provider          VARCHAR(32) NOT NULL,
    window_start      TIMESTAMPTZ NOT NULL,
    window_end        TIMESTAMPTZ NOT NULL,
    subject_mode      VARCHAR(32) NOT NULL
                      CHECK (subject_mode IN ('INTERNAL_LEDGER','PROVIDER_ONLY','BOTH')),
    status            VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    total_subjects    INTEGER     NOT NULL DEFAULT 0,
    processed_subjects INTEGER    NOT NULL DEFAULT 0,
    -- summary figures, written on completion (04 §8)
    matched_count             INTEGER NOT NULL DEFAULT 0,
    matched_not_settled_count INTEGER NOT NULL DEFAULT 0,
    mismatched_count          INTEGER NOT NULL DEFAULT 0,
    missing_on_provider_count INTEGER NOT NULL DEFAULT 0,
    missing_internal_count    INTEGER NOT NULL DEFAULT 0,
    duplicate_count           INTEGER NOT NULL DEFAULT 0,
    ambiguous_count           INTEGER NOT NULL DEFAULT 0,
    gross_delta_minor         BIGINT  NOT NULL DEFAULT 0,
    cases_opened              INTEGER NOT NULL DEFAULT 0,
    cases_reused              INTEGER NOT NULL DEFAULT 0,
    failure_reason            VARCHAR(500),
    started_by                VARCHAR(64) NOT NULL,
    request_id                VARCHAR(64),
    started_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at              TIMESTAMPTZ,

    CONSTRAINT ck_batch_window CHECK (window_end > window_start)
);

-- the frozen subject population, so a resumed batch compares exactly the same set (04 §7)
CREATE TABLE reconciliation_subjects (
    batch_id               TEXT NOT NULL REFERENCES reconciliation_batches (id),
    subject_key            VARCHAR(160) NOT NULL,
    provider               VARCHAR(32) NOT NULL,
    external_transaction_id VARCHAR(64) NOT NULL,
    side                   VARCHAR(8)  NOT NULL CHECK (side IN ('BOTH','INTERNAL','PROVIDER')),
    claimed_at             TIMESTAMPTZ,
    PRIMARY KEY (batch_id, subject_key)
);

CREATE INDEX ix_subjects_unclaimed ON reconciliation_subjects (batch_id, claimed_at);

CREATE TABLE reconciliation_results (
    id                       TEXT PRIMARY KEY,
    batch_id                 TEXT NOT NULL REFERENCES reconciliation_batches (id),
    subject_key              VARCHAR(160) NOT NULL,
    outcome                  VARCHAR(32) NOT NULL
        CHECK (outcome IN ('MATCHED','MATCHED_NOT_SETTLED','AMOUNT_MISMATCH','CURRENCY_MISMATCH',
                           'STATUS_MISMATCH','REFERENCE_MISMATCH','SETTLEMENT_DATE_MISMATCH',
                           'MISSING_ON_PROVIDER','MISSING_INTERNAL',
                           'DUPLICATE_PROVIDER_RECORD','AMBIGUOUS_MATCH')),
    internal_payment_id      TEXT REFERENCES payments (id),
    provider_transaction_id  VARCHAR(64),
    expected_gross_minor     BIGINT, expected_fee_minor BIGINT, expected_net_minor BIGINT,
    expected_currency        CHAR(3),  expected_status   VARCHAR(16),
    actual_gross_minor       BIGINT,  actual_fee_minor  BIGINT, actual_net_minor  BIGINT,
    actual_currency          CHAR(3),  actual_status     VARCHAR(24),
    delta_minor              BIGINT,
    differences              JSONB     NOT NULL DEFAULT '[]'::jsonb,
    case_id                  TEXT,
    classified_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- the resume guard: one result per subject per batch, no matter how many attempts
    CONSTRAINT ux_result_subject UNIQUE (batch_id, subject_key)
);

CREATE INDEX ix_results_batch_outcome ON reconciliation_results (batch_id, outcome);
CREATE INDEX ix_results_case          ON reconciliation_results (case_id);
CREATE INDEX ix_results_delta         ON reconciliation_results (batch_id, delta_minor)
    WHERE delta_minor IS NOT NULL AND delta_minor <> 0;

CREATE TRIGGER tg_results_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_results
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();

CREATE TABLE reconciliation_cases (
    id                 TEXT PRIMARY KEY,
    case_number        BIGINT GENERATED ALWAYS AS IDENTITY,
    batch_id           TEXT NOT NULL REFERENCES reconciliation_batches (id),
    result_id          TEXT NOT NULL REFERENCES reconciliation_results (id),
    payment_id         TEXT REFERENCES payments (id),
    provider_transaction_id VARCHAR(64),
    reason             VARCHAR(32) NOT NULL,
    severity           VARCHAR(8)  NOT NULL CHECK (severity IN ('HIGH','MEDIUM','LOW')),
    status             VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN','INVESTIGATING','RESOLVED','WRITTEN_OFF')),
    occurrence_count   INTEGER     NOT NULL DEFAULT 1,
    assigned_to        VARCHAR(64),
    resolution_note    VARCHAR(2000),
    resolution_action  VARCHAR(32),
    adjustment_ledger_transaction_id TEXT REFERENCES ledger_transactions (id),
    detected_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_case_resolution CHECK (
        (status IN ('RESOLVED','WRITTEN_OFF')
           AND resolution_note IS NOT NULL
           AND length(resolution_note) >= 20
           AND resolved_at IS NOT NULL)
     OR (status IN ('OPEN','INVESTIGATING') AND resolved_at IS NULL)
    )
);

-- one open case per subject per reason: re-running reconciliation increments occurrence_count
-- instead of creating duplicates (04 §6)
CREATE UNIQUE INDEX ux_case_open_subject_reason
    ON reconciliation_cases (COALESCE(payment_id, '~'), provider_transaction_id, reason)
    WHERE status IN ('OPEN','INVESTIGATING');

CREATE INDEX ix_cases_status ON reconciliation_cases (status, severity, detected_at DESC);
CREATE INDEX ix_cases_number ON reconciliation_cases (case_number DESC);

CREATE TABLE case_events (
    id         TEXT PRIMARY KEY,
    case_id    TEXT NOT NULL REFERENCES reconciliation_cases (id),
    from_status VARCHAR(16),
    to_status  VARCHAR(16) NOT NULL,
    note       VARCHAR(2000),
    actor_type VARCHAR(16) NOT NULL,
    actor_id   VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_case_events ON case_events (case_id, occurred_at, id);

CREATE TRIGGER tg_case_events_immutable
    BEFORE UPDATE OR DELETE ON case_events
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();
```

## 8. Audit

```sql
CREATE TABLE audit_events (
    id             TEXT PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type     VARCHAR(16) NOT NULL
                   CHECK (actor_type IN ('USER','SERVICE','WORKER','WEBHOOK','SYSTEM')),
    actor_id       VARCHAR(64) NOT NULL,
    action         VARCHAR(48) NOT NULL,
    entity_type    VARCHAR(32) NOT NULL,
    entity_id      VARCHAR(128),
    request_id     VARCHAR(64),
    correlation_id VARCHAR(64),
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_audit_entity  ON audit_events (entity_type, entity_id, occurred_at, id);
CREATE INDEX ix_audit_action  ON audit_events (action, occurred_at DESC);
CREATE INDEX ix_audit_request ON audit_events (request_id);
CREATE INDEX ix_audit_time    ON audit_events (occurred_at DESC);
CREATE INDEX ix_audit_meta    ON audit_events USING GIN (metadata);

CREATE TRIGGER tg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION fn_deny_mutation();
```

Complete `action` enumeration:

```
PAYMENT_CREATED              PAYMENT_AUTHORIZED        PAYMENT_CAPTURED
PAYMENT_FAILED               PAYMENT_SETTLED           PAYMENT_REFUNDED
LEDGER_POSTED                LEDGER_REVERSAL           LEDGER_POST_REJECTED
WEBHOOK_ACCEPTED             WEBHOOK_DUPLICATE         WEBHOOK_REJECTED
WEBHOOK_PROCESSED            WEBHOOK_NO_EFFECT         WEBHOOK_FAILED
SETTLEMENT_INGESTED          PROVIDER_SYNC_COMPLETED
RECONCILIATION_BATCH_STARTED RECONCILIATION_BATCH_COMPLETED  RECONCILIATION_BATCH_FAILED
RECONCILIATION_CASE_OPENED   RECONCILIATION_CASE_UPDATED
RECONCILIATION_CASE_RESOLVED RECONCILIATION_CASE_WRITTEN_OFF
IDEMPOTENCY_KEY_REPLAYED     IDEMPOTENCY_KEY_CONFLICT  IDEMPOTENCY_KEY_RELEASED
LEDGER_BALANCE_VERIFIED      LEDGER_BALANCE_BREACH
```

## 9. Table-to-invariant map

| Invariant | Enforced by |
| --- | --- |
| L1 balances | Java validator + `tg_txn_balances` deferred trigger |
| L2 single currency | `ledger_transactions.currency` column + trigger's `mixed` check |
| L3 shape | Java validator + `ck_entry` checks (`amount_minor > 0`) |
| L4/L5 immutability | `tg_ledger_transactions_immutable`, `tg_ledger_entries_immutable` |
| L6 one reversal | `ux_ledger_reversal` unique index |
| L7 projection integrity | `LedgerVerifier` + integration test |
| L8 no negative assets | `tg_asset_non_negative` |
| L9 active accounts | `LedgerService` check + `ledger_accounts.state` |
| L10 no double post | `ux_ledger_source` partial unique index (`type <> 'REVERSAL'`) |
| Duplicate webhook | `ux_provider_event` unique index |
| One open case | `ux_case_open_subject_reason` partial unique index |
| Resumable batches | `ux_result_subject` + `reconciliation_subjects.claimed_at` |
| L11 a payment is paid out at most once | `ux_payout_line_payment` unique index on `payout_lines (payment_id)` |

Eleven of the thirteen rows above are enforced by the database rather than only by Java. The two
exceptions are deliberate and are called out so the count can be checked: **L7** is verified after
the fact by an independent recomputation rather than on the write path (recomputing a balance on
every insert would turn each posting into a full scan of the account's history), and **L9** is a
service-level check against `ledger_accounts.state`.

That is the intended shape: **the invariants a financial system cannot afford to lose belong in
the storage layer.**