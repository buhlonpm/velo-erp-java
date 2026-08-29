-- Справочник тарифов и привязка к моделям велосипедов
CREATE TABLE tariffs (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    unit       VARCHAR(10)  NOT NULL, -- hour / day / week / month
    price      INT          NOT NULL CHECK (price > 0),
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (name, unit)
);

CREATE TABLE bike_model_tariffs (
    model_id  UUID NOT NULL REFERENCES bike_models (id) ON DELETE CASCADE,
    tariff_id UUID NOT NULL REFERENCES tariffs (id) ON DELETE CASCADE,
    PRIMARY KEY (model_id, tariff_id)
);

-- Позиция аренды: снапшот тарифа (единица + цена за единицу)
ALTER TABLE rental_items ADD COLUMN tariff_unit VARCHAR(10) NOT NULL DEFAULT 'hour';
ALTER TABLE rental_items RENAME COLUMN rate_per_hour TO rate;
