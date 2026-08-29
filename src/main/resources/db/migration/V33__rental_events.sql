-- Лента событий аренды: создание, продление, возврат позиции, возврат денег, отмена.
-- Продление несёт структурные поля сдвига срока (duration + unit, from_end_at → to_end_at).
CREATE TABLE rental_events (
    id             UUID PRIMARY KEY,
    rental_id      UUID        NOT NULL REFERENCES rentals (id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    date           TIMESTAMP   NOT NULL,
    comment        TEXT        NOT NULL DEFAULT '',
    amount         INT,
    transaction_id UUID        REFERENCES finance_transactions (id),
    duration       INT,
    duration_unit  VARCHAR(10),
    from_end_at    TIMESTAMP,
    to_end_at      TIMESTAMP,
    created_by     UUID        REFERENCES users (id),
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_rental_events_rental_id ON rental_events (rental_id);
