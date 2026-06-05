CREATE TABLE categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    slug       VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE products (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID         NOT NULL REFERENCES categories(id),
    title          VARCHAR(500) NOT NULL,
    description    TEXT,
    -- price stored in minor units (paise/cents) to avoid floating point money
    price_amount   BIGINT       NOT NULL CHECK (price_amount >= 0),
    currency       CHAR(3)      NOT NULL DEFAULT 'INR',
    stock_quantity INTEGER      NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    image_url      TEXT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version        INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- Postgres full-text search vector, maintained by the DB (MVP: no Elasticsearch)
    search_vector  TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED
);

CREATE INDEX idx_products_category_status ON products(category_id, status);
CREATE INDEX idx_products_status_created  ON products(status, created_at DESC);
CREATE INDEX idx_products_search          ON products USING GIN (search_vector);
