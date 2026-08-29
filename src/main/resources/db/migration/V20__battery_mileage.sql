-- Журнал пробега становится общим для всех активов (АКБ копит пробег от велосипеда)
ALTER TABLE bike_mileage_logs RENAME TO asset_mileage_logs;

-- У АКБ свой накопленный пробег
ALTER TABLE asset_battery_details ADD COLUMN mileage_km INT NOT NULL DEFAULT 0;
