CREATE TABLE bikes (
    id               UUID PRIMARY KEY,
    inventory_number VARCHAR(50)  NOT NULL UNIQUE,
    model            VARCHAR(255) NOT NULL,
    battery_level    INT          NOT NULL DEFAULT 100 CHECK (battery_level BETWEEN 0 AND 100),
    mileage_km       INT          NOT NULL DEFAULT 0,
    rate_per_hour    INT          NOT NULL CHECK (rate_per_hour > 0),
    status           VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE customers (
    id         UUID PRIMARY KEY,
    full_name  VARCHAR(255) NOT NULL,
    phone      VARCHAR(32)  NOT NULL,
    email      VARCHAR(255) NOT NULL DEFAULT '',
    deposit    INT          NOT NULL DEFAULT 0,
    note       TEXT         NOT NULL DEFAULT '',
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE rentals (
    id             UUID PRIMARY KEY,
    customer_id    UUID      NOT NULL REFERENCES customers (id),
    bike_id        UUID      NOT NULL REFERENCES bikes (id),
    start_at       TIMESTAMP NOT NULL,
    planned_end_at TIMESTAMP NOT NULL,
    returned_at    TIMESTAMP,
    amount         INT,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_rentals_bike_id ON rentals (bike_id);
CREATE INDEX idx_rentals_customer_id ON rentals (customer_id);

CREATE TABLE maintenance_records (
    id         UUID PRIMARY KEY,
    bike_id    UUID      NOT NULL REFERENCES bikes (id),
    date       TIMESTAMP NOT NULL,
    type       VARCHAR(20) NOT NULL,
    cost       INT       NOT NULL DEFAULT 0,
    comment    TEXT      NOT NULL DEFAULT '',
    status     VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_maintenance_records_bike_id ON maintenance_records (bike_id);
