-- Износ велосипеда по пробегу: макс. пробег и остаточная стоимость (% цены покупки) — справочно, на модели
ALTER TABLE bike_models ADD COLUMN max_mileage_km INTEGER CHECK (max_mileage_km > 0);
ALTER TABLE bike_models ADD COLUMN residual_percent INTEGER CHECK (residual_percent BETWEEN 0 AND 100);
