-- Resource services resolve only the membership/quota facts they need. They
-- never receive direct SELECT access to Identity's user/member/plan tables.
CREATE FUNCTION identity.is_active_tenant(p_tenant_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, identity
AS $$
    SELECT EXISTS (
        SELECT 1 FROM identity.tenants t
        WHERE t.id = p_tenant_id AND t.status = 'ACTIVE'
    );
$$;

CREATE FUNCTION identity.effective_tenant_quota(p_tenant_id UUID, p_default_plan TEXT)
RETURNS TABLE(max_cameras INTEGER, max_active_broadcasts INTEGER)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, identity
AS $$
    SELECT COALESCE(o.max_cameras, p.max_cameras),
           COALESCE(o.max_active_broadcasts, p.max_active_broadcasts)
    FROM identity.tenants t
    LEFT JOIN identity.tenant_quota_assignments a ON a.tenant_id = t.id
    JOIN identity.quota_plans p
      ON p.plan_code = COALESCE(a.plan_code, p_default_plan)
     AND p.enabled = TRUE
    LEFT JOIN identity.tenant_quota_overrides o ON o.tenant_id = t.id
    WHERE t.id = p_tenant_id AND t.status = 'ACTIVE';
$$;

CREATE FUNCTION identity.resolve_tenant_membership(
    p_issuer TEXT, p_subject TEXT, p_tenant_id UUID
)
RETURNS TABLE(
    tenant_id UUID, user_id UUID, tenant_slug TEXT,
    tenant_name TEXT, role_code TEXT
)
LANGUAGE SQL
	STABLE
SECURITY DEFINER
SET search_path = pg_catalog, identity
AS $$
    SELECT m.tenant_id, m.user_id, t.slug, t.display_name, m.role_code::TEXT
    FROM identity.users u
    JOIN identity.tenant_memberships m ON m.user_id = u.id
    JOIN identity.tenants t ON t.id = m.tenant_id
    WHERE u.issuer = p_issuer AND u.subject = p_subject AND u.enabled = TRUE
      AND m.tenant_id = p_tenant_id AND m.status = 'ACTIVE' AND t.status = 'ACTIVE';
$$;

CREATE FUNCTION identity.resolve_active_role(p_user_id UUID, p_tenant_id UUID)
RETURNS TABLE(role_code TEXT)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, identity
AS $$
    SELECT m.role_code::TEXT
    FROM identity.tenant_memberships m
    JOIN identity.tenants t ON t.id = m.tenant_id
    JOIN identity.users u ON u.id = m.user_id
    WHERE m.user_id = p_user_id AND m.tenant_id = p_tenant_id
      AND m.status = 'ACTIVE' AND t.status = 'ACTIVE' AND u.enabled = TRUE;
$$;

REVOKE ALL ON FUNCTION identity.is_active_tenant(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION identity.effective_tenant_quota(UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION identity.resolve_tenant_membership(TEXT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION identity.resolve_active_role(UUID, UUID) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'camera_runtime')
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stream_runtime') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION identity.is_active_tenant(UUID) TO camera_runtime, stream_runtime';
        EXECUTE 'GRANT EXECUTE ON FUNCTION identity.effective_tenant_quota(UUID, TEXT) TO camera_runtime, stream_runtime';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'camera_runtime')
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stream_runtime')
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'websocket_runtime') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION identity.resolve_tenant_membership(TEXT, TEXT, UUID) TO camera_runtime, stream_runtime, websocket_runtime';
        EXECUTE 'GRANT EXECUTE ON FUNCTION identity.resolve_active_role(UUID, UUID) TO camera_runtime, stream_runtime, websocket_runtime';
    END IF;
END;
$$;
