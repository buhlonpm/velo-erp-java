-- Тарифы привязываются к модели напрямую (1:N), таблица связей больше не нужна.
ALTER TABLE tariffs ADD COLUMN model_id UUID REFERENCES bike_models (id) ON DELETE CASCADE;

-- Переносим существующие привязки (у каждого тарифа сейчас максимум одна модель)
UPDATE tariffs t
SET model_id = bmt.model_id
FROM bike_model_tariffs bmt
WHERE bmt.tariff_id = t.id;

-- Тарифы без модели (непривязанные) удаляем — таких быть не должно, но на всякий случай
DELETE FROM tariffs WHERE model_id IS NULL;

ALTER TABLE tariffs ALTER COLUMN model_id SET NOT NULL;
ALTER TABLE tariffs DROP CONSTRAINT tariffs_name_unit_key;
ALTER TABLE tariffs ADD CONSTRAINT tariffs_model_name_unit_key UNIQUE (model_id, name, unit);

DROP TABLE bike_model_tariffs;

-- Тариф по умолчанию у модели больше не нужен: цены живут в тарифах,
-- почасовая ставка конкретного велосипеда — в assets.rate_per_hour
ALTER TABLE bike_models DROP COLUMN default_rate_per_hour;
