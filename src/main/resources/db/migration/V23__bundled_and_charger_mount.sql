-- «В комплекте с велосипедом» для АКБ и зарядников + монтаж зарядника на велосипед
ALTER TABLE asset_battery_details ADD COLUMN bundled_bike_id UUID REFERENCES assets (id);
ALTER TABLE asset_charger_details ADD COLUMN bundled_bike_id UUID REFERENCES assets (id);
ALTER TABLE asset_charger_details ADD COLUMN bike_id UUID REFERENCES assets (id);
