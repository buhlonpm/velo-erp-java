-- Тариф модели привязан к виду договора: аренда (любая единица) или выкуп (строго неделя, один на модель)
ALTER TABLE tariffs
    ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'rent';

ALTER TABLE tariffs DROP CONSTRAINT tariffs_model_name_unit_key;
ALTER TABLE tariffs ADD CONSTRAINT tariffs_model_name_unit_kind_key UNIQUE (model_id, name, unit, kind);
