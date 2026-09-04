CREATE TABLE app_metadata (
    id UUID PRIMARY KEY,
    key VARCHAR(100) NOT NULL UNIQUE,
    value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_metadata (id, key, value)
VALUES ('11111111-1111-1111-1111-111111111111', 'bootstrap.version', '0.1.0')
ON CONFLICT (key) DO NOTHING;


CREATE TABLE app_users (
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

CREATE INDEX idx_app_user_created_at ON app_users (created_at);

INSERT INTO app_users (
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

CREATE TABLE app_domains (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    domain_name VARCHAR(255) NOT NULL,
    verification_code VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_domain_name UNIQUE (domain_name)
);

CREATE TABLE gateways (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    name VARCHAR(100) NOT NULL,
    unique_key VARCHAR(64) NOT NULL UNIQUE,
    description TEXT,
    app_domain_id UUID NOT NULL REFERENCES app_domains(id) ON DELETE CASCADE ON UPDATE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gateway_unique_key UNIQUE (unique_key)
);

CREATE TABLE certificates (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id) ON DELETE CASCADE ON UPDATE CASCADE,
    hostname VARCHAR(255) NOT NULL,
    wildcard_hostname VARCHAR(255),
    provider VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    renewed_at TIMESTAMP WITH TIME ZONE,
    certificate_data TEXT NOT NULL,
    private_key_data TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_certificates_gateway_id ON certificates (gateway_id);
CREATE INDEX idx_certificates_hostname ON certificates (hostname);
CREATE INDEX idx_certificates_expires_at ON certificates (expires_at);
