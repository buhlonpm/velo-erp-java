-- Велосипед: дата/сумма покупки, опциональный GPS-трекер
ALTER TABLE asset_bike_details ADD COLUMN purchased_at TIMESTAMP;
ALTER TABLE asset_bike_details ADD COLUMN purchase_price INT CHECK (purchase_price >= 0);
ALTER TABLE asset_bike_details ADD COLUMN gps_model VARCHAR(100);
ALTER TABLE asset_bike_details ADD COLUMN gps_sim_number VARCHAR(32);
ALTER TABLE asset_bike_details ADD COLUMN gps_operator VARCHAR(50);

-- Привязка финансовой операции к активу (ремонт велосипеда, выплата за повреждение и т.п.)
ALTER TABLE finance_transactions ADD COLUMN asset_id UUID REFERENCES assets (id);
CREATE INDEX idx_finance_transactions_asset_id ON finance_transactions (asset_id);
