-- SIM-карты: покупка (может быть 0 — в комплекте с трекером) и выбытие (продать нельзя)
ALTER TABLE sim_cards ADD COLUMN purchased_at TIMESTAMP;
ALTER TABLE sim_cards ADD COLUMN purchase_price INT CHECK (purchase_price >= 0);
ALTER TABLE sim_cards ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE sim_cards ADD COLUMN write_off_reason VARCHAR(20);

-- Трекеры: продажа и комментарий при выбытии
ALTER TABLE gps_trackers ADD COLUMN write_off_comment TEXT;
