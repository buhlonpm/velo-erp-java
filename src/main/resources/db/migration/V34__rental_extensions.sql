-- Продления аренды отдельной сущностью: продление можно править/удалять с пересчётом срока.
-- from_end_at — якорь продления (max(plannedEndAt, момент продления): при просрочке продлеваем от «сейчас»),
-- to_end_at — новый конец периода.
CREATE TABLE rental_extensions (
    id            UUID PRIMARY KEY,
    rental_id     UUID        NOT NULL REFERENCES rentals (id) ON DELETE CASCADE,
    duration      INT         NOT NULL,
    duration_unit VARCHAR(10) NOT NULL,
    from_end_at   TIMESTAMP   NOT NULL,
    to_end_at     TIMESTAMP   NOT NULL,
    created_by    UUID        REFERENCES users (id),
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_rental_extensions_rental_id ON rental_extensions (rental_id);
