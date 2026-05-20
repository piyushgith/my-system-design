CREATE TABLE cif.customers (
    cif_id          VARCHAR(20) PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    date_of_birth   DATE NOT NULL,
    gender          VARCHAR(10),
    pan_hash        VARCHAR(64) NOT NULL,
    aadhaar_token   VARCHAR(72),
    customer_type   VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    risk_rating     VARCHAR(10) NOT NULL DEFAULT 'LOW',
    pep_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE cif.kyc_records (
    kyc_id          UUID NOT NULL DEFAULT RANDOM_UUID() PRIMARY KEY,
    cif_id          VARCHAR(20) NOT NULL,
    kyc_type        VARCHAR(30) NOT NULL DEFAULT 'FULL',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_by     VARCHAR(50),
    verified_at     TIMESTAMP WITH TIME ZONE,
    expiry_date     DATE,
    document_refs   VARCHAR(4000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_kyc_customer FOREIGN KEY (cif_id) REFERENCES cif.customers(cif_id)
);
