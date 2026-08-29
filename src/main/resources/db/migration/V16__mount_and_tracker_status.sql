-- АКБ монтируется на велосипед
ALTER TABLE asset_battery_details ADD COLUMN bike_id UUID REFERENCES assets (id);

-- Трекер: покупка и статус (active / written_off)
ALTER TABLE gps_trackers ADD COLUMN purchased_at TIMESTAMP;
ALTER TABLE gps_trackers ADD COLUMN purchase_price INT CHECK (purchase_price >= 0);
ALTER TABLE gps_trackers ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- С какого велосипеда списан (для roll-up «вложено» — безвозвратная потеря)
ALTER TABLE gps_trackers ADD COLUMN written_off_from_bike_id UUID REFERENCES assets (id);
