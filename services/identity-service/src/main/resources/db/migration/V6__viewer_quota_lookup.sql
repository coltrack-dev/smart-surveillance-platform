CREATE FUNCTION identity.effective_viewer_quota(p_tenant_id UUID, p_default_plan TEXT)
RETURNS TABLE(max_concurrent_viewers INTEGER)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, identity
AS $$
    SELECT COALESCE(o.max_concurrent_viewers, p.max_concurrent_viewers)
    FROM identity.tenants t
    LEFT JOIN identity.tenant_quota_assignments a ON a.tenant_id = t.id
    JOIN identity.quota_plans p
      ON p.plan_code = COALESCE(a.plan_code, p_default_plan)
     AND p.enabled = TRUE
    LEFT JOIN identity.tenant_quota_overrides o ON o.tenant_id = t.id
    WHERE t.id = p_tenant_id AND t.status = 'ACTIVE';
$$;

REVOKE ALL ON FUNCTION identity.effective_viewer_quota(UUID, TEXT) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stream_runtime') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION identity.effective_viewer_quota(UUID, TEXT) TO stream_runtime';
    END IF;
END;
$$;
