CREATE TABLE orders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id         UUID         NOT NULL REFERENCES users(id),
    status           VARCHAR(30)  NOT NULL DEFAULT 'PLACED',
    total_amount     BIGINT       NOT NULL CHECK (total_amount >= 0),
    currency         CHAR(3)      NOT NULL DEFAULT 'INR',
    payment_method   VARCHAR(20)  NOT NULL DEFAULT 'COD',
    shipping_address TEXT         NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    version          INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       UUID         NOT NULL REFERENCES orders(id),
    product_id     UUID         NOT NULL,
    title_snapshot VARCHAR(500) NOT NULL,
    quantity       INTEGER      NOT NULL CHECK (quantity > 0),
    unit_price     BIGINT       NOT NULL,
    total_price    BIGINT       NOT NULL
);

CREATE INDEX idx_orders_buyer_created ON orders(buyer_id, created_at DESC);
CREATE INDEX idx_orders_status        ON orders(status, updated_at);
CREATE INDEX idx_order_items_order    ON order_items(order_id);
