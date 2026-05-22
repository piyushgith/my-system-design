CREATE TABLE delivery_partners (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    phone            VARCHAR(20)  NOT NULL UNIQUE,
    email            VARCHAR(255) NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    vehicle_type     VARCHAR(20)  NOT NULL,
    vehicle_number   VARCHAR(20)  NOT NULL,
    status           VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    is_online        BOOLEAN      NOT NULL DEFAULT FALSE,
    rating           NUMERIC(3,2) NOT NULL DEFAULT 0.0,
    total_deliveries INTEGER      NOT NULL DEFAULT 0,
    city_id          VARCHAR(50)  NOT NULL,
    joined_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE deliveries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                UUID         NOT NULL UNIQUE REFERENCES orders(id),
    partner_id              UUID         NOT NULL REFERENCES delivery_partners(id),
    restaurant_lat          NUMERIC(9,6) NOT NULL,
    restaurant_lng          NUMERIC(9,6) NOT NULL,
    customer_lat            NUMERIC(9,6) NOT NULL,
    customer_lng            NUMERIC(9,6) NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'ASSIGNED',
    estimated_pickup_time   TIMESTAMP,
    actual_pickup_time      TIMESTAMP,
    estimated_delivery_time TIMESTAMP,
    actual_delivery_time    TIMESTAMP,
    distance_km             NUMERIC(7,3),
    delivery_fee_amount     BIGINT       NOT NULL DEFAULT 0,
    partner_earning_amount  BIGINT       NOT NULL DEFAULT 0,
    failure_reason          VARCHAR(300),
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_partners_city   ON delivery_partners(city_id, is_online, status);
CREATE INDEX idx_delivery_partners_phone  ON delivery_partners(phone);
CREATE INDEX idx_deliveries_partner       ON deliveries(partner_id, status);
CREATE INDEX idx_deliveries_order         ON deliveries(order_id);
