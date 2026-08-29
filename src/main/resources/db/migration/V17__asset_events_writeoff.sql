-- Журнал событий актива (биография железа: покупка, монтаж, пробег, трекер, выбытие)
CREATE TABLE asset_events (
    id             UUID PRIMARY KEY,
    asset_id       UUID      NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    date           TIMESTAMP NOT NULL,
    comment        TEXT      NOT NULL DEFAULT '',
    amount         INT,
    transaction_id UUID      REFERENCES finance_transactions (id),
    created_by     UUID      REFERENCES users (id),
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_events_asset_id ON asset_events (asset_id);

-- Выбытие актива: причина, дата, атрибуция (на велосипед / общий флот)
ALTER TABLE assets ADD COLUMN write_off_reason VARCHAR(20);
ALTER TABLE assets ADD COLUMN written_off_at TIMESTAMP;
ALTER TABLE assets ADD COLUMN written_off_attributed_to UUID REFERENCES assets (id);

-- Причина выбытия трекера
ALTER TABLE gps_trackers ADD COLUMN write_off_reason VARCHAR(20);
