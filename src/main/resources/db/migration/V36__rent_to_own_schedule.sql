-- Договор «под выкуп»: срок в неделях (13/26/52) + график еженедельных платежей.
-- Активы после полного выкупа уходят клиенту — статус BOUGHT_OUT (колонка VARCHAR, ограничений нет).

ALTER TABLE rentals ADD COLUMN term_weeks INT;

CREATE TABLE rental_schedule_items (
    id         UUID PRIMARY KEY,
    rental_id  UUID        NOT NULL REFERENCES rentals (id) ON DELETE CASCADE,
    seq        INT         NOT NULL,
    due_date   TIMESTAMPTZ NOT NULL,
    amount     INT         NOT NULL CHECK (amount > 0),
    -- сколько из платежей отнесено на строку (разносится сервисом; перестроенные строки = 0)
    covered_amount INT     NOT NULL DEFAULT 0 CHECK (covered_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_schedule_items_rental ON rental_schedule_items (rental_id);
