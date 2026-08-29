-- VIN у велосипеда и АКБ (необязательный); инвентарный номер остаётся отдельным полем
ALTER TABLE asset_bike_details ADD COLUMN vin VARCHAR(50);
ALTER TABLE asset_battery_details ADD COLUMN vin VARCHAR(50);
