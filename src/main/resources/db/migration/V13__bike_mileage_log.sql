-- Велосипед: батарея % уходит (АКБ — отдельные активы)
ALTER TABLE asset_bike_details DROP COLUMN battery_level;

-- Журнал пробега: каждая запись «дата + значение», текущий пробег = последняя запись
CREATE TABLE bike_mileage_logs (
    id          UUID PRIMARY KEY,
    asset_id    UUID      NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    mileage_km  INT       NOT NULL CHECK (mileage_km >= 0),
    recorded_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_bike_mileage_logs_asset_id ON bike_mileage_logs (asset_id);
