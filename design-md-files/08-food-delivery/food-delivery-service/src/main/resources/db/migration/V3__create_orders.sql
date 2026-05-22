CREATE TABLE orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             UUID         NOT NULL REFERENCES users(id),
    restaurant_id           UUID         NOT NULL REFERENCES restaurants(id),
    delivery_address_id     UUID         NOT NULL REFERENCES addresses(id),
    status                  VARCHAR(40)  NOT NULL,
    saga_state              VARCHAR(40),
    subtotal_amount         BIGINT       NOT NULL,
    subtotal_currency       VARCHAR(3)   NOT NULL DEFAULT 'INR',
    delivery_fee_amount     BIGINT       NOT NULL DEFAULT 0,
    discount_amount         BIGINT       NOT NULL DEFAULT 0,
    platform_fee_amount     BIGINT       NOT NULL DEFAULT 0,
    total_amount            BIGINT       NOT NULL,
    payment_method          VARCHAR(20)  NOT NULL,
    payment_id              UUID,
    delivery_partner_id     UUID,
    estimated_delivery_time TIMESTAMP,
    actual_delivery_time    TIMESTAMP,
    special_instructions    VARCHAR(200),
    idempotency_key         VARCHAR(100) NOT NULL UNIQUE,
    cancelled_by            VARCHAR(20),
    cancellation_reason     VARCHAR(300),
    city_id                 VARCHAR(50)  NOT NULL,
    version                 INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID         NOT NULL REFERENCES orders(id),
    menu_item_id        UUID         NOT NULL,
    menu_item_name      VARCHAR(200) NOT NULL,
    unit_price_amount   BIGINT       NOT NULL,
    unit_price_currency VARCHAR(3)   NOT NULL DEFAULT 'INR',
    quantity            INTEGER      NOT NULL,
    total_price_amount  BIGINT       NOT NULL,
    customizations      VARCHAR(300)
);

CREATE TABLE payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                UUID         NOT NULL UNIQUE REFERENCES orders(id),
    customer_id             UUID         NOT NULL REFERENCES users(id),
    amount                  BIGINT       NOT NULL,
    currency                VARCHAR(3)   NOT NULL DEFAULT 'INR',
    method                  VARCHAR(20)  NOT NULL,
    status                  VARCHAR(30)  NOT NULL,
    gateway_provider        VARCHAR(30),
    gateway_transaction_id  VARCHAR(200),
    gateway_response        JSONB,
    refund_amount           BIGINT,
    refund_initiated_at     TIMESTAMP,
    refund_completed_at     TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE reviews (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID         NOT NULL UNIQUE REFERENCES orders(id),
    customer_id         UUID         NOT NULL REFERENCES users(id),
    restaurant_id       UUID         NOT NULL REFERENCES restaurants(id),
    delivery_partner_id UUID,
    restaurant_rating   SMALLINT     NOT NULL CHECK (restaurant_rating BETWEEN 1 AND 5),
    delivery_rating     SMALLINT     CHECK (delivery_rating BETWEEN 1 AND 5),
    review_text         VARCHAR(500),
    tags                TEXT[]       NOT NULL DEFAULT '{}',
    is_visible          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_customer      ON orders(customer_id, created_at DESC);
CREATE INDEX idx_orders_restaurant    ON orders(restaurant_id, status, created_at);
CREATE INDEX idx_orders_status_city   ON orders(status, city_id, created_at);
CREATE INDEX idx_orders_partner       ON orders(delivery_partner_id, status);
CREATE INDEX idx_order_items_order    ON order_items(order_id);
CREATE INDEX idx_payments_order       ON payments(order_id);
CREATE INDEX idx_reviews_restaurant   ON reviews(restaurant_id, is_visible);
