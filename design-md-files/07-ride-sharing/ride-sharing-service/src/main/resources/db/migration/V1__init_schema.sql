-- Ride-sharing service — initial schema (PostgreSQL-compatible; H2 uses MODE=PostgreSQL)

CREATE TABLE cities (
    city_id       UUID PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    country_code  VARCHAR(2)   NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

CREATE TABLE riders (
    rider_id      UUID PRIMARY KEY,
    phone_number  VARCHAR(20)  NOT NULL UNIQUE,
    email         VARCHAR(255),
    full_name     VARCHAR(255) NOT NULL,
    rating        NUMERIC(3, 2) NOT NULL DEFAULT 5.00,
    total_trips   INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    last_trip_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE drivers (
    driver_id                UUID PRIMARY KEY,
    phone_number             VARCHAR(20)  NOT NULL UNIQUE,
    email                    VARCHAR(255),
    full_name                VARCHAR(255) NOT NULL,
    rating                   NUMERIC(3, 2) NOT NULL DEFAULT 5.00,
    total_trips              INT          NOT NULL DEFAULT 0,
    background_check_status  VARCHAR(16)  NOT NULL DEFAULT 'APPROVED'
        CHECK (background_check_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    onboarding_status        VARCHAR(16)  NOT NULL DEFAULT 'APPROVED'
        CHECK (onboarding_status IN ('INCOMPLETE', 'PENDING_REVIEW', 'APPROVED', 'SUSPENDED')),
    city_id                  UUID         NOT NULL REFERENCES cities(city_id),
    created_at               TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_drivers_city_id ON drivers(city_id);

CREATE TABLE vehicles (
    vehicle_id            UUID PRIMARY KEY,
    driver_id             UUID         NOT NULL REFERENCES drivers(driver_id),
    registration_number   VARCHAR(64)  NOT NULL UNIQUE,
    vehicle_type          VARCHAR(16)  NOT NULL
        CHECK (vehicle_type IN ('ECONOMY', 'PREMIUM', 'SUV', 'AUTO', 'BIKE')),
    make                  VARCHAR(128) NOT NULL,
    model                 VARCHAR(128) NOT NULL,
    model_year            INT          NOT NULL,
    color                 VARCHAR(64)  NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    verified_at           TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vehicles_driver_id ON vehicles(driver_id);

CREATE TABLE fare_quotes (
    quote_id                UUID PRIMARY KEY,
    rider_id                UUID         NOT NULL REFERENCES riders(rider_id),
    pickup_lat              NUMERIC(10, 7) NOT NULL,
    pickup_lng              NUMERIC(10, 7) NOT NULL,
    destination_lat         NUMERIC(10, 7) NOT NULL,
    destination_lng         NUMERIC(10, 7) NOT NULL,
    vehicle_type            VARCHAR(16)  NOT NULL
        CHECK (vehicle_type IN ('ECONOMY', 'PREMIUM', 'SUV', 'AUTO', 'BIKE')),
    base_fare               INT          NOT NULL,
    distance_fare           INT          NOT NULL,
    time_fare               INT          NOT NULL,
    surge_multiplier        NUMERIC(4, 2) NOT NULL,
    platform_fee            INT          NOT NULL,
    total_fare_min          INT          NOT NULL,
    total_fare_max          INT          NOT NULL,
    city_id                 UUID         NOT NULL REFERENCES cities(city_id),
    estimated_distance_km   NUMERIC(8, 2) NOT NULL,
    estimated_duration_min  INT          NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMP WITH TIME ZONE  NOT NULL,
    used                    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_fare_quotes_rider_id ON fare_quotes(rider_id);
CREATE INDEX idx_fare_quotes_expires_at ON fare_quotes(expires_at);

CREATE TABLE trips (
    trip_id                  UUID PRIMARY KEY,
    rider_id                 UUID         NOT NULL REFERENCES riders(rider_id),
    driver_id                UUID         REFERENCES drivers(driver_id),
    vehicle_id               UUID         REFERENCES vehicles(vehicle_id),
    status                   VARCHAR(24)  NOT NULL DEFAULT 'REQUESTED',
    vehicle_type_requested   VARCHAR(16)  NOT NULL
        CHECK (vehicle_type_requested IN ('ECONOMY', 'PREMIUM', 'SUV', 'AUTO', 'BIKE')),
    pickup_lat               NUMERIC(10, 7) NOT NULL,
    pickup_lng               NUMERIC(10, 7) NOT NULL,
    pickup_address           VARCHAR(512) NOT NULL,
    destination_lat          NUMERIC(10, 7) NOT NULL,
    destination_lng          NUMERIC(10, 7) NOT NULL,
    destination_address      VARCHAR(512) NOT NULL,
    estimated_distance_km    NUMERIC(8, 2) NOT NULL,
    actual_distance_km       NUMERIC(8, 2),
    estimated_duration_min   INT          NOT NULL,
    actual_duration_min      INT,
    estimated_fare_min       INT          NOT NULL,
    estimated_fare_max       INT          NOT NULL,
    surge_multiplier         NUMERIC(4, 2) NOT NULL,
    final_fare               INT,
    payment_id               UUID,
    cancellation_reason      VARCHAR(255),
    cancelled_by             VARCHAR(32),
    cancellation_fee         INT,
    city_id                  UUID         NOT NULL REFERENCES cities(city_id),
    otp                      VARCHAR(4)   NOT NULL,
    requested_at             TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    matched_at               TIMESTAMP WITH TIME ZONE,
    driver_arrived_at        TIMESTAMP WITH TIME ZONE,
    trip_started_at          TIMESTAMP WITH TIME ZONE,
    trip_ended_at            TIMESTAMP WITH TIME ZONE,
    cancelled_at             TIMESTAMP WITH TIME ZONE,
    version                  BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_trips_rider_history ON trips(rider_id, requested_at DESC);
CREATE INDEX idx_trips_city_date ON trips(city_id, requested_at DESC);
CREATE INDEX idx_trips_status_city ON trips(status, city_id);

CREATE INDEX idx_trips_rider_active ON trips(rider_id, status);

CREATE INDEX idx_trips_driver_active ON trips(driver_id, status);

CREATE TABLE trip_events (
    event_id         UUID PRIMARY KEY,
    trip_id          UUID         NOT NULL REFERENCES trips(trip_id),
    event_type       VARCHAR(64)  NOT NULL,
    previous_status  VARCHAR(24),
    new_status       VARCHAR(24),
    actor_id         UUID         NOT NULL,
    actor_type       VARCHAR(32)  NOT NULL,
    occurred_at      TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trip_events_trip_id ON trip_events(trip_id, occurred_at DESC);

CREATE TABLE trip_offers (
    offer_id        UUID PRIMARY KEY,
    trip_id         UUID         NOT NULL REFERENCES trips(trip_id),
    driver_id       UUID         NOT NULL REFERENCES drivers(driver_id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
    matching_round  INT          NOT NULL,
    offered_at      TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE  NOT NULL
);

CREATE INDEX idx_trip_offers_trip_id ON trip_offers(trip_id, offered_at DESC);
CREATE INDEX idx_trip_offers_driver_pending ON trip_offers(driver_id, status);

CREATE TABLE payments (
    payment_id              UUID PRIMARY KEY,
    trip_id                 UUID         NOT NULL UNIQUE REFERENCES trips(trip_id),
    rider_id                UUID         NOT NULL REFERENCES riders(rider_id),
    driver_id               UUID         NOT NULL REFERENCES drivers(driver_id),
    amount                  INT          NOT NULL,
    currency                VARCHAR(3)   NOT NULL DEFAULT 'INR',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED')),
    driver_share            INT          NOT NULL,
    platform_commission     INT          NOT NULL,
    idempotency_key         VARCHAR(128) NOT NULL UNIQUE,
    gateway_transaction_id  VARCHAR(128),
    initiated_at            TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    captured_at             TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_payments_driver_id ON payments(driver_id);

CREATE TABLE ratings (
    rating_id         UUID PRIMARY KEY,
    trip_id           UUID         NOT NULL REFERENCES trips(trip_id),
    rated_by          VARCHAR(16)  NOT NULL,
    rated_entity_id   UUID         NOT NULL,
    score             INT          NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment           VARCHAR(1024),
    created_at        TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_ratings_trip_rated_by ON ratings(trip_id, rated_by);

CREATE TABLE idempotency_records (
    idempotency_key  VARCHAR(64) PRIMARY KEY,
    http_status      INT          NOT NULL,
    response_body    TEXT         NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

-- Spring Modulith JPA event publication registry (see Modulith appendix D)
CREATE TABLE event_publication (
    id                     UUID PRIMARY KEY,
    listener_id            VARCHAR(512) NOT NULL,
    event_type             VARCHAR(512) NOT NULL,
    serialized_event       VARCHAR(4000) NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 VARCHAR(20),
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_event_publication_incomplete ON event_publication(publication_date);
