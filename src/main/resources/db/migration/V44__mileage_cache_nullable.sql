-- Кэш текущего пробега может быть NULL: пустой журнал (удалили единственную запись) → null
ALTER TABLE asset_bike_details ALTER COLUMN mileage_km DROP NOT NULL;
ALTER TABLE asset_bike_details ALTER COLUMN mileage_km DROP DEFAULT;
ALTER TABLE asset_battery_details ALTER COLUMN mileage_km DROP NOT NULL;
ALTER TABLE asset_battery_details ALTER COLUMN mileage_km DROP DEFAULT;
