-- Assumed Claims expert schema. Idempotent, forward-only DDL (SPEC 16.9.8).
-- Every table is tenant-scoped; the business book is fixed to ASSUMED on the claim aggregate.

CREATE SEQUENCE IF NOT EXISTS assumed_claim_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS assumed_cash_call_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS assumed_commutation_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS assumed_dispute_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS assumed_claim_task_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS assumed_claim_note_number_seq START 1;

CREATE TABLE IF NOT EXISTS assumed_claim (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    business_book VARCHAR(16) NOT NULL DEFAULT 'ASSUMED',
    assumed_claim_number VARCHAR(32) NOT NULL,
    contract_id UUID NOT NULL,
    cedent_claim_number VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    loss_date DATE,
    report_date DATE,
    reserve_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reserve_currency CHAR(3) NOT NULL DEFAULT 'USD',
    assigned_adjuster VARCHAR(128),
    assigned_team VARCHAR(64),
    assigned_at TIMESTAMPTZ,
    assigned_by VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_claim_number UNIQUE (tenant_id, business_book, assumed_claim_number),
    CONSTRAINT ck_assumed_claim_book CHECK (business_book = 'ASSUMED'),
    CONSTRAINT ck_assumed_claim_report_date CHECK (loss_date IS NULL OR report_date IS NULL OR report_date >= loss_date),
    CONSTRAINT ck_assumed_claim_reserve CHECK (reserve_amount >= 0),
    CONSTRAINT ck_assumed_claim_currency CHECK (reserve_currency ~ '^[A-Z]{3}$')
);

-- Forward-only addition of the claim assignment fields for databases created
-- before the assignment slice. ADD COLUMN IF NOT EXISTS keeps re-running the
-- script idempotent on both a fresh and an existing database.
ALTER TABLE assumed_claim ADD COLUMN IF NOT EXISTS assigned_adjuster VARCHAR(128);
ALTER TABLE assumed_claim ADD COLUMN IF NOT EXISTS assigned_team VARCHAR(64);
ALTER TABLE assumed_claim ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ;
ALTER TABLE assumed_claim ADD COLUMN IF NOT EXISTS assigned_by VARCHAR(128);

CREATE INDEX IF NOT EXISTS ix_assumed_claim_tenant_status
    ON assumed_claim (tenant_id, status);

CREATE INDEX IF NOT EXISTS ix_assumed_claim_tenant_assignment
    ON assumed_claim (tenant_id, assigned_team, assigned_adjuster);

CREATE TABLE IF NOT EXISTS assumed_bordereau (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    bordereau_reference VARCHAR(64) NOT NULL,
    cedent_claim_number VARCHAR(64),
    advice_date DATE NOT NULL,
    reported_amount_total NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'BORDEREAU',
    version INTEGER NOT NULL DEFAULT 1,
    line_count INTEGER NOT NULL DEFAULT 0,
    accepted_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_bordereau_reference UNIQUE (tenant_id, bordereau_reference),
    CONSTRAINT ck_assumed_bordereau_amount CHECK (reported_amount_total >= 0),
    CONSTRAINT ck_assumed_bordereau_counts CHECK (
        line_count >= 0 AND accepted_count >= 0 AND duplicate_count >= 0 AND rejected_count >= 0
    ),
    CONSTRAINT ck_assumed_bordereau_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_bordereau_claim
    ON assumed_bordereau (tenant_id, assumed_claim_id, received_at DESC);

CREATE TABLE IF NOT EXISTS assumed_loss_advice (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    bordereau_id UUID REFERENCES assumed_bordereau (id),
    bordereau_line_number INTEGER,
    advice_number VARCHAR(32) NOT NULL,
    cedent_claim_number VARCHAR(64),
    advice_type VARCHAR(24) NOT NULL,
    advice_date DATE NOT NULL,
    received_date DATE NOT NULL,
    period_from DATE,
    period_to DATE,
    reported_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(19, 4),
    outstanding_amount NUMERIC(19, 4),
    expense_amount NUMERIC(19, 4),
    reserve_amount NUMERIC(19, 4),
    recovery_amount NUMERIC(19, 4),
    currency CHAR(3) NOT NULL,
    cession_pct NUMERIC(9, 6),
    ceded_amount NUMERIC(19, 4),
    layer_attachment_met BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL,
    rejection_reason VARCHAR(200),
    source_document_id UUID,
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    version INTEGER NOT NULL DEFAULT 1,
    posted_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_loss_advice_number UNIQUE (tenant_id, assumed_claim_id, advice_number),
    CONSTRAINT ck_assumed_loss_advice_reported CHECK (reported_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_paid CHECK (paid_amount IS NULL OR paid_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_outstanding CHECK (outstanding_amount IS NULL OR outstanding_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_expense CHECK (expense_amount IS NULL OR expense_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_reserve CHECK (reserve_amount IS NULL OR reserve_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_recovery CHECK (recovery_amount IS NULL OR recovery_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_ceded CHECK (ceded_amount IS NULL OR ceded_amount >= 0),
    CONSTRAINT ck_assumed_loss_advice_cession CHECK (cession_pct IS NULL OR (cession_pct >= 0 AND cession_pct <= 1)),
    CONSTRAINT ck_assumed_loss_advice_period CHECK (period_from IS NULL OR period_to IS NULL OR period_to >= period_from),
    CONSTRAINT ck_assumed_loss_advice_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_loss_advice_claim
    ON assumed_loss_advice (tenant_id, assumed_claim_id, advice_date DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_assumed_loss_advice_status
    ON assumed_loss_advice (tenant_id, assumed_claim_id, status);

CREATE TABLE IF NOT EXISTS assumed_reserve_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    component VARCHAR(24) NOT NULL,
    sequence INTEGER NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    reason_code VARCHAR(24) NOT NULL,
    reason_text VARCHAR(200),
    authority VARCHAR(24) NOT NULL,
    authority_required BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_reserve_sequence UNIQUE (tenant_id, assumed_claim_id, component, sequence),
    CONSTRAINT ck_assumed_reserve_amount CHECK (amount >= 0),
    CONSTRAINT ck_assumed_reserve_authority CHECK (
        (authority_required = FALSE)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT ck_assumed_reserve_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_reserve_current
    ON assumed_reserve_history (tenant_id, assumed_claim_id, is_current, component);
CREATE INDEX IF NOT EXISTS ix_assumed_reserve_effective
    ON assumed_reserve_history (tenant_id, assumed_claim_id, component, effective_date DESC, sequence DESC);

CREATE TABLE IF NOT EXISTS assumed_cash_call (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    request_number VARCHAR(32) NOT NULL,
    requested_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    requested_at DATE NOT NULL,
    due_date DATE,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(24) NOT NULL,
    reason_text TEXT,
    approved_by UUID,
    approver_role VARCHAR(24),
    approved_at TIMESTAMPTZ,
    approval_status VARCHAR(16) NOT NULL,
    payment_id UUID,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_cash_call_number UNIQUE (tenant_id, request_number),
    CONSTRAINT ck_assumed_cash_call_amount CHECK (requested_amount > 0),
    CONSTRAINT ck_assumed_cash_call_due CHECK (due_date IS NULL OR due_date >= requested_at),
    CONSTRAINT ck_assumed_cash_call_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_cash_call_claim
    ON assumed_cash_call (tenant_id, assumed_claim_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_assumed_cash_call_status
    ON assumed_cash_call (tenant_id, assumed_claim_id, status);

CREATE TABLE IF NOT EXISTS assumed_recovery (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    recovery_type VARCHAR(24) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    received_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_party_id UUID,
    source_contract_id UUID,
    expected_amount NUMERIC(19, 4),
    payment_id UUID,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_assumed_recovery_amount CHECK (amount > 0),
    CONSTRAINT ck_assumed_recovery_expected CHECK (expected_amount IS NULL OR expected_amount >= 0),
    CONSTRAINT ck_assumed_recovery_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_recovery_claim
    ON assumed_recovery (tenant_id, assumed_claim_id, created_at DESC);

CREATE TABLE IF NOT EXISTS assumed_commutation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    commutation_number VARCHAR(32) NOT NULL,
    commutation_type VARCHAR(16) NOT NULL,
    offer_amount NUMERIC(19, 4) NOT NULL,
    accepted_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    valuation_date DATE,
    basis VARCHAR(16) NOT NULL,
    release_document_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    approved_by UUID NOT NULL,
    approver_role VARCHAR(24) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    savings_amount NUMERIC(19, 4),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_commutation_number UNIQUE (tenant_id, commutation_number),
    CONSTRAINT uq_assumed_commutation_claim UNIQUE (tenant_id, assumed_claim_id),
    CONSTRAINT ck_assumed_commutation_offer CHECK (offer_amount >= 0),
    CONSTRAINT ck_assumed_commutation_accepted CHECK (accepted_amount >= 0),
    CONSTRAINT ck_assumed_commutation_savings CHECK (savings_amount IS NULL OR savings_amount >= 0),
    CONSTRAINT ck_assumed_commutation_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_commutation_claim
    ON assumed_commutation (tenant_id, assumed_claim_id);

CREATE TABLE IF NOT EXISTS assumed_dispute (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    dispute_number VARCHAR(32) NOT NULL,
    dispute_type VARCHAR(24) NOT NULL,
    reason TEXT NOT NULL,
    contested_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    resolution TEXT,
    raised_at DATE NOT NULL,
    resolved_at DATE,
    arbitration_flag BOOLEAN NOT NULL DEFAULT FALSE,
    raised_by_party_id UUID,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_dispute_number UNIQUE (tenant_id, dispute_number),
    CONSTRAINT ck_assumed_dispute_amount CHECK (contested_amount >= 0),
    CONSTRAINT ck_assumed_dispute_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_dispute_claim
    ON assumed_dispute (tenant_id, assumed_claim_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_assumed_dispute_status
    ON assumed_dispute (tenant_id, assumed_claim_id, status);

-- ---------------------------------------------------------------------------
-- Claim tasks and diary notes. Both are child records of the Assumed Claim
-- aggregate, tenant- and claim-scoped, and append their domain event through
-- the outbox in the same transaction. Notes are append-only: there is no
-- update or delete. The tables and constraints mirror the Direct Claims
-- claim_task and claim_note tables, scoped to assumed_claim_id.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_task (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id) ON DELETE CASCADE,
    task_number VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    due_date DATE NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_claim_task_number UNIQUE (tenant_id, task_number),
    CONSTRAINT ck_claim_task_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_claim_task_status CHECK (status IN ('OPEN', 'COMPLETE', 'CANCELLED')),
    CONSTRAINT ck_claim_task_completed CHECK (
        (status = 'COMPLETE' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETE' AND completed_at IS NULL))
);

CREATE INDEX IF NOT EXISTS ix_claim_task_status_due
    ON claim_task (tenant_id, assumed_claim_id, status, due_date);
CREATE INDEX IF NOT EXISTS ix_claim_task_created
    ON claim_task (tenant_id, assumed_claim_id, created_at DESC);

CREATE TABLE IF NOT EXISTS claim_note (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id) ON DELETE CASCADE,
    note_number VARCHAR(32) NOT NULL,
    note_type VARCHAR(24) NOT NULL,
    body TEXT NOT NULL,
    author VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_claim_note_number UNIQUE (tenant_id, note_number),
    CONSTRAINT ck_claim_note_type CHECK (note_type IN (
        'DIARY', 'COVERAGE', 'RESERVE', 'LITIGATION', 'SETTLEMENT', 'GENERAL'))
);

CREATE INDEX IF NOT EXISTS ix_claim_note_created
    ON claim_note (tenant_id, assumed_claim_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Command idempotency for claim task and note creation. Scope is tenant +
-- command type + key, so the same key can be used once for a task and once for
-- a note. response_body is the original 201 projection serialized as JSON and
-- is replayed verbatim. The reservation, the created child row, its outbox
-- event, and the response body share one transaction: a rollback leaves no key
-- behind and a replay writes no duplicate row or event.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_command_idempotency (
    tenant_id UUID NOT NULL,
    command_type VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_claim_command_idempotency
        PRIMARY KEY (tenant_id, command_type, idempotency_key),
    CONSTRAINT ck_claim_command_idempotency_type
        CHECK (command_type IN ('CLAIM_TASK_CREATE', 'CLAIM_NOTE_CREATE')),
    CONSTRAINT ck_claim_command_idempotency_key
        CHECK (CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 64),
    CONSTRAINT ck_claim_command_idempotency_hash
        CHECK (CHAR_LENGTH(request_hash) = 64)
);

CREATE INDEX IF NOT EXISTS ix_claim_command_idempotency_claim
    ON claim_command_idempotency (tenant_id, claim_id, command_type);

CREATE TABLE IF NOT EXISTS assumed_claim_audit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assumed_claim_id UUID NOT NULL REFERENCES assumed_claim (id),
    event_type VARCHAR(48) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    sequence BIGINT GENERATED BY DEFAULT AS IDENTITY
);

CREATE INDEX IF NOT EXISTS ix_assumed_claim_audit_claim
    ON assumed_claim_audit (tenant_id, assumed_claim_id, occurred_at, sequence);

-- The audit trail is append-only: UPDATE and DELETE are refused by the database, not only by code.
-- The function body is single-quoted (with doubled quotes) so Spring's script splitter does not
-- mistake the inner semicolon for a statement delimiter.
CREATE OR REPLACE FUNCTION assumed_claim_audit_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS 'BEGIN RAISE EXCEPTION ''assumed_claim_audit is append-only''; END';

DROP TRIGGER IF EXISTS trg_assumed_claim_audit_immutable ON assumed_claim_audit;
CREATE TRIGGER trg_assumed_claim_audit_immutable
    BEFORE UPDATE OR DELETE ON assumed_claim_audit
    FOR EACH ROW
    EXECUTE FUNCTION assumed_claim_audit_immutable();

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
