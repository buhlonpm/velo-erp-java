CREATE TABLE user_permissions (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, permission)
);
