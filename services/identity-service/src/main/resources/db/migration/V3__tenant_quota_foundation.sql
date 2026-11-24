CREATE TABLE identity.quota_plans (
    plan_code VARCHAR(32) PRIMARY KEY
        CHECK (plan_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    display_name VARCHAR(80) NOT NULL,
    max_cameras INTEGER NOT NULL CHECK (max_cameras >= 0),
    max_active_broadcasts INTEGER NOT NULL CHECK (max_active_broadcasts >= 0),
    max_concurrent_viewers INTEGER NOT NULL CHECK (max_concurrent_viewers >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO identity.quota_plans
    (plan_code, display_name, max_cameras, max_active_broadcasts, max_concurrent_viewers)
VALUES
    ('FREE', 'Free', 2, 1, 3),
    ('PRO', 'Pro', 10, 5, 25),
    ('BUSINESS', 'Business', 50, 25, 200)
ON CONFLICT (plan_code) DO NOTHING;

CREATE TABLE identity.tenant_quota_assignments (
    tenant_id UUID PRIMARY KEY REFERENCES identity.tenants(id) ON DELETE CASCADE,
    plan_code VARCHAR(32) NOT NULL REFERENCES identity.quota_plans(plan_code),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_identity_quota_assignments_plan
    ON identity.tenant_quota_assignments (plan_code, tenant_id);

CREATE TABLE identity.tenant_quota_overrides (
    tenant_id UUID PRIMARY KEY REFERENCES identity.tenants(id) ON DELETE CASCADE,
    max_cameras INTEGER CHECK (max_cameras IS NULL OR max_cameras >= 0),
    max_active_broadcasts INTEGER CHECK (max_active_broadcasts IS NULL OR max_active_broadcasts >= 0),
    max_concurrent_viewers INTEGER CHECK (max_concurrent_viewers IS NULL OR max_concurrent_viewers >= 0),
    reason VARCHAR(500),
    updated_by_user_id UUID REFERENCES identity.users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_quota_override_not_empty CHECK (
        max_cameras IS NOT NULL OR max_active_broadcasts IS NOT NULL OR max_concurrent_viewers IS NOT NULL
    )
);
