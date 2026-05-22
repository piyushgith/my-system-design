CREATE TABLE restaurant_owners (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    phone         VARCHAR(20)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE restaurants (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id                UUID         NOT NULL REFERENCES restaurant_owners(id),
    name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    cuisine_types           TEXT[]       NOT NULL DEFAULT '{}',
    status                  VARCHAR(30)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    is_open                 BOOLEAN      NOT NULL DEFAULT FALSE,
    city_id                 VARCHAR(50)  NOT NULL,
    latitude                NUMERIC(9,6) NOT NULL,
    longitude               NUMERIC(9,6) NOT NULL,
    delivery_radius_meters  INTEGER      NOT NULL DEFAULT 5000,
    minimum_order_amount    BIGINT       NOT NULL DEFAULT 0,
    avg_prep_time_minutes   INTEGER      NOT NULL DEFAULT 30,
    rating                  NUMERIC(3,2) NOT NULL DEFAULT 0.0,
    total_ratings           INTEGER      NOT NULL DEFAULT 0,
    commission_rate         NUMERIC(5,4) NOT NULL DEFAULT 0.15,
    logo_url                VARCHAR(500),
    onboarded_at            TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE menu_categories (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID         NOT NULL REFERENCES restaurants(id),
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    image_url     VARCHAR(500)
);

CREATE TABLE menu_items (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id            UUID         NOT NULL REFERENCES menu_categories(id),
    restaurant_id          UUID         NOT NULL REFERENCES restaurants(id),
    name                   VARCHAR(200) NOT NULL,
    description            TEXT,
    price_amount           BIGINT       NOT NULL,
    price_currency         VARCHAR(3)   NOT NULL DEFAULT 'INR',
    discounted_price_amount BIGINT,
    is_vegetarian          BOOLEAN      NOT NULL DEFAULT FALSE,
    is_vegan               BOOLEAN      NOT NULL DEFAULT FALSE,
    is_available           BOOLEAN      NOT NULL DEFAULT TRUE,
    prep_time_minutes      INTEGER      NOT NULL DEFAULT 15,
    tags                   TEXT[]       NOT NULL DEFAULT '{}',
    allergens              TEXT[]       NOT NULL DEFAULT '{}',
    image_url              VARCHAR(500),
    display_order          INTEGER      NOT NULL DEFAULT 0,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_restaurants_city_status   ON restaurants(city_id, status, is_open);
CREATE INDEX idx_restaurants_owner         ON restaurants(owner_id);
CREATE INDEX idx_menu_categories_rest      ON menu_categories(restaurant_id);
CREATE INDEX idx_menu_items_restaurant     ON menu_items(restaurant_id, is_available);
CREATE INDEX idx_menu_items_category       ON menu_items(category_id, display_order);
