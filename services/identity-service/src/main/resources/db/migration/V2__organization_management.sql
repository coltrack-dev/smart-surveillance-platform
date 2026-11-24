CREATE TABLE identity.tenant_invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES identity.tenants(id),
    invited_email_normalized TEXT NOT NULL
        CHECK (invited_email_normalized = lower(btrim(invited_email_normalized))),
    role_code VARCHAR(16) NOT NULL REFERENCES identity.roles(code)
        CHECK (role_code IN ('OWNER', 'ADMIN', 'OPERATOR', 'VIEWER')),
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES identity.users(id),
    accepted_by_user_id UUID REFERENCES identity.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_identity_pending_invitation_email
    ON identity.tenant_invitations (tenant_id, invited_email_normalized)
    WHERE status = 'PENDING';
CREATE INDEX ix_identity_invitation_expiry
    ON identity.tenant_invitations (expires_at, tenant_id)
    WHERE status = 'PENDING';
CREATE INDEX ix_identity_invitation_tenant_pending_created
    ON identity.tenant_invitations (tenant_id, created_at DESC)
    WHERE status = 'PENDING';
CREATE INDEX ix_identity_memberships_tenant_status_role
    ON identity.tenant_memberships (tenant_id, status, role_code);

-- Deferred constraint triggers permit creating a tenant and its first OWNER in
-- one transaction while preventing committed tenants with no active OWNER.
CREATE FUNCTION identity.assert_tenant_has_active_owner(target_tenant UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM identity.tenants WHERE id = target_tenant FOR UPDATE;
    IF FOUND AND NOT EXISTS (
        SELECT 1 FROM identity.tenant_memberships
        WHERE tenant_id = target_tenant AND status = 'ACTIVE' AND role_code = 'OWNER'
    ) THEN
        RAISE EXCEPTION 'tenant must have an active OWNER'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_identity_tenant_active_owner';
    END IF;
END;
$$;

CREATE FUNCTION identity.check_tenant_active_owner_from_membership()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM identity.assert_tenant_has_active_owner(OLD.tenant_id);
    END IF;
    IF TG_OP <> 'DELETE' AND (TG_OP = 'INSERT' OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id) THEN
        PERFORM identity.assert_tenant_has_active_owner(NEW.tenant_id);
    ELSIF TG_OP <> 'DELETE' AND NEW.tenant_id = OLD.tenant_id THEN
        PERFORM identity.assert_tenant_has_active_owner(NEW.tenant_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_identity_membership_active_owner
AFTER INSERT OR UPDATE OR DELETE ON identity.tenant_memberships
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION identity.check_tenant_active_owner_from_membership();

CREATE FUNCTION identity.check_tenant_active_owner_from_tenant()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM identity.assert_tenant_has_active_owner(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_identity_tenant_active_owner
AFTER INSERT OR UPDATE ON identity.tenants
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION identity.check_tenant_active_owner_from_tenant();
