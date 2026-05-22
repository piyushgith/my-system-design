-- V1: Chart of Accounts
CREATE TABLE accounts (
    account_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code    VARCHAR(64) NOT NULL UNIQUE,
    account_name    VARCHAR(255) NOT NULL,
    account_type    VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance  VARCHAR(6)  NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    currency        CHAR(3)     NOT NULL,
    owner_id        UUID,
    owner_type      VARCHAR(20) CHECK (owner_type IN ('USER','ORGANIZATION','INTERNAL')),
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    metadata        JSONB
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id, owner_type) WHERE status = 'ACTIVE';
CREATE INDEX idx_accounts_code  ON accounts(account_code);
CREATE INDEX idx_accounts_type  ON accounts(account_type);
