CREATE SEQUENCE accounts.account_number_seq START WITH 10000001 INCREMENT BY 1;

CREATE TABLE accounts.accounts (
    account_id              VARCHAR(25) PRIMARY KEY,
    cif_id                  VARCHAR(20) NOT NULL,
    account_type            VARCHAR(20) NOT NULL,
    product_code            VARCHAR(30) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    currency                CHAR(3) NOT NULL DEFAULT 'INR',
    current_balance_paise   BIGINT NOT NULL DEFAULT 0,
    available_balance_paise BIGINT NOT NULL DEFAULT 0,
    overdraft_limit_paise   BIGINT NOT NULL DEFAULT 0,
    open_date               DATE NOT NULL,
    closure_date            DATE,
    last_txn_date           DATE,
    dormancy_date           DATE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_accounts_cif FOREIGN KEY (cif_id) REFERENCES cif.customers(cif_id)
);

CREATE TABLE accounts.liens (
    lien_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      VARCHAR(25) NOT NULL REFERENCES accounts.accounts(account_id),
    amount_paise    BIGINT NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    lien_type       VARCHAR(30),
    reference_id    VARCHAR(50),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    released_at     TIMESTAMPTZ,
    released_by     VARCHAR(50)
);
