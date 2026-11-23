CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
    id UUID PRIMARY KEY,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    email TEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    display_name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_identity_users_issuer_subject UNIQUE (issuer, subject)
);

CREATE TABLE identity.tenants (
    id UUID PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE identity.roles (
    code VARCHAR(16) PRIMARY KEY
        CHECK (code IN ('OWNER', 'ADMIN', 'OPERATOR', 'VIEWER')),
    description TEXT NOT NULL
);

INSERT INTO identity.roles (code, description) VALUES
    ('OWNER', 'Tenant owner'),
    ('ADMIN', 'Tenant administrator'),
    ('OPERATOR', 'Camera and broadcast operator'),
    ('VIEWER', 'Read-only tenant viewer')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE identity.tenant_memberships (
    tenant_id UUID NOT NULL REFERENCES identity.tenants(id),
    user_id UUID NOT NULL REFERENCES identity.users(id),
    role_code VARCHAR(16) NOT NULL REFERENCES identity.roles(code),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE INDEX ix_identity_memberships_user_status
    ON identity.tenant_memberships (user_id, status, tenant_id);
