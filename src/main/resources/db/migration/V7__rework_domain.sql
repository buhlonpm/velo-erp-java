-- Чистка таблиц из V3, под которые не было кода (bikes, rentals, maintenance_records).
-- customers остаётся — под неё теперь появляется сущность.
DROP TABLE IF EXISTS rentals;
DROP TABLE IF EXISTS maintenance_records;
DROP TABLE IF EXISTS bikes;

-- Справочник моделей велосипедов (ведёт админ в настройках)
CREATE TABLE bike_models (
    id                    UUID PRIMARY KEY,
    brand                 VARCHAR(100) NOT NULL,
    model                 VARCHAR(150) NOT NULL,
    specs                 VARCHAR(255) NOT NULL DEFAULT '',
    default_rate_per_hour INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (brand, model)
);

-- Активы: велосипеды, аккумуляторы, зарядные устройства.
-- Общие поля здесь, типо-специфичные — в деталь-таблицах 1:1.
CREATE TABLE assets (
    id               UUID PRIMARY KEY,
    type             VARCHAR(20) NOT NULL,
    inventory_number VARCHAR(50) NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    rate_per_hour    INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE TABLE asset_bike_details (
    asset_id      UUID PRIMARY KEY REFERENCES assets (id) ON DELETE CASCADE,
    model_id      UUID REFERENCES bike_models (id),
    battery_level INT NOT NULL DEFAULT 100 CHECK (battery_level BETWEEN 0 AND 100),
    mileage_km    INT NOT NULL DEFAULT 0
);

CREATE TABLE asset_battery_details (
    asset_id    UUID PRIMARY KEY REFERENCES assets (id) ON DELETE CASCADE,
    voltage     INT,
    capacity_ah INT
);

CREATE TABLE asset_charger_details (
    asset_id  UUID PRIMARY KEY REFERENCES assets (id) ON DELETE CASCADE,
    power_w   INT,
    connector VARCHAR(50)
);

-- Аренда: заголовок + позиции. kind: RENT / RENT_TO_OWN (под выкуп).
CREATE TABLE rentals (
    id             UUID PRIMARY KEY,
    customer_id    UUID      NOT NULL REFERENCES customers (id),
    kind           VARCHAR(20) NOT NULL DEFAULT 'RENT',
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_at       TIMESTAMP NOT NULL,
    planned_end_at TIMESTAMP,
    deposit        INT       NOT NULL DEFAULT 0,
    buyout_price   INT,
    comment        TEXT      NOT NULL DEFAULT '',
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_rentals_customer_id ON rentals (customer_id);

CREATE TABLE rental_items (
    id            UUID PRIMARY KEY,
    rental_id     UUID      NOT NULL REFERENCES rentals (id) ON DELETE CASCADE,
    asset_id      UUID      NOT NULL REFERENCES assets (id),
    rate_per_hour INT       NOT NULL DEFAULT 0,
    returned_at   TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_rental_items_rental_id ON rental_items (rental_id);
CREATE INDEX idx_rental_items_asset_id ON rental_items (asset_id);

-- Привязка платежей к аренде (оплата аренды, выкупные платежи)
ALTER TABLE finance_transactions ADD COLUMN rental_id UUID REFERENCES rentals (id);
