-- Комментарий списания SIM-карты (заполняется при каскадном списании вместе с трекером)
ALTER TABLE sim_cards ADD COLUMN write_off_comment TEXT;
