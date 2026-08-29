-- Журнал циклов перезарядки АКБ: каждая запись «дата + значение», текущее значение = последняя по дате запись
CREATE TABLE asset_charge_cycle_logs (
    id          UUID PRIMARY KEY,
    asset_id    UUID      NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    cycles      INT       NOT NULL CHECK (cycles >= 0),
    recorded_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_charge_cycle_logs_asset_id ON asset_charge_cycle_logs (asset_id);
