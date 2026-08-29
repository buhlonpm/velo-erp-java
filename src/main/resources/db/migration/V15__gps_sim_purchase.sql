-- Справочники SIM-карт и GPS-трекеров
CREATE TABLE sim_cards (
    id           UUID PRIMARY KEY,
    phone_number VARCHAR(32) NOT NULL UNIQUE,
    operator     VARCHAR(50) NOT NULL,
    note         VARCHAR(255) NOT NULL DEFAULT '',
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE gps_trackers (
    id          UUID PRIMARY KEY,
    model       VARCHAR(100) NOT NULL,
    imei        VARCHAR(32),
    sim_card_id UUID REFERENCES sim_cards (id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- Покупка и описание — для всех активов (переносим из bike_details)
ALTER TABLE assets ADD COLUMN purchased_at TIMESTAMP;
ALTER TABLE assets ADD COLUMN purchase_price INT CHECK (purchase_price >= 0);
ALTER TABLE assets ADD COLUMN description TEXT NOT NULL DEFAULT '';

UPDATE assets a
SET purchased_at = d.purchased_at, purchase_price = d.purchase_price
FROM asset_bike_details d
WHERE d.asset_id = a.id;

ALTER TABLE asset_bike_details DROP COLUMN purchased_at;
ALTER TABLE asset_bike_details DROP COLUMN purchase_price;

-- GPS-трекер велосипеда — ссылка на справочник вместо текстовых полей
ALTER TABLE asset_bike_details DROP COLUMN gps_model;
ALTER TABLE asset_bike_details DROP COLUMN gps_sim_number;
ALTER TABLE asset_bike_details DROP COLUMN gps_operator;
ALTER TABLE asset_bike_details ADD COLUMN gps_tracker_id UUID REFERENCES gps_trackers (id);
