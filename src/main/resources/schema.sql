CREATE SEQUENCE IF NOT EXISTS assumed_claim_number_seq START 1;

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
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_assumed_claim_number UNIQUE (tenant_id, business_book, assumed_claim_number),
    CONSTRAINT ck_assumed_claim_book CHECK (business_book = 'ASSUMED'),
    CONSTRAINT ck_assumed_claim_report_date CHECK (loss_date IS NULL OR report_date IS NULL OR report_date >= loss_date),
    CONSTRAINT ck_assumed_claim_reserve CHECK (reserve_amount >= 0),
    CONSTRAINT ck_assumed_claim_currency CHECK (reserve_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_assumed_claim_tenant_status
    ON assumed_claim (tenant_id, status);
