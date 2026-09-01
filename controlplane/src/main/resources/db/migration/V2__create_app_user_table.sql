CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE INDEX idx_app_user_created_at ON app_user (created_at);

INSERT INTO app_user (
    id,
    username,
    email,
    full_name,
    password_hash,
    password_change_required
)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    'admin',
    'admin@example.com',
    'FuncHole Admin',
    '$2a$12$rVJ9WKIpyrCXY3od0G6kYOTtAcgwPmqvCLQaJZMAYVyvXlOfkJepq',
    TRUE
)
ON CONFLICT (username) DO NOTHING;

CREATE TABLE gateway (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    domain VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gateway_name UNIQUE (name)
)