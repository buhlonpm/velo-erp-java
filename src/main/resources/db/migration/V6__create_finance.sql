CREATE TABLE finance_accounts (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    initial_balance INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE finance_categories (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    kind       VARCHAR(10)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (name, kind)
);

CREATE TABLE finance_transactions (
    id          UUID PRIMARY KEY,
    account_id  UUID      NOT NULL REFERENCES finance_accounts (id),
    category_id UUID      NOT NULL REFERENCES finance_categories (id),
    kind        VARCHAR(10) NOT NULL,
    amount      INT       NOT NULL CHECK (amount > 0),
    date        TIMESTAMP NOT NULL,
    comment     TEXT      NOT NULL DEFAULT '',
    created_by  UUID      REFERENCES users (id),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_finance_transactions_account_id ON finance_transactions (account_id);
CREATE INDEX idx_finance_transactions_category_id ON finance_transactions (category_id);
