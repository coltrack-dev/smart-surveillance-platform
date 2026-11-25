-- Existing Streaming installation conversion; see docs/saas/USAGE_QUOTAS.md.
-- Run once as the current database superuser after streaming-db-roles.sql and
-- before starting application services with their new runtime identities.
ALTER TABLE identity.users OWNER TO identity_migrator;
ALTER TABLE identity.tenants OWNER TO identity_migrator;
ALTER TABLE identity.roles OWNER TO identity_migrator;
ALTER TABLE identity.tenant_memberships OWNER TO identity_migrator;
ALTER TABLE identity.tenant_invitations OWNER TO identity_migrator;
ALTER TABLE identity.quota_plans OWNER TO identity_migrator;
ALTER TABLE identity.tenant_quota_assignments OWNER TO identity_migrator;
ALTER TABLE identity.tenant_quota_overrides OWNER TO identity_migrator;
ALTER TABLE identity.flyway_identity_schema_history OWNER TO identity_migrator;

ALTER FUNCTION identity.assert_tenant_has_active_owner(uuid) OWNER TO identity_migrator;
ALTER FUNCTION identity.check_tenant_active_owner_from_membership() OWNER TO identity_migrator;
ALTER FUNCTION identity.check_tenant_active_owner_from_tenant() OWNER TO identity_migrator;

ALTER TABLE public.cameras OWNER TO camera_migrator;
ALTER TABLE public.camera_categories OWNER TO camera_migrator;
ALTER TABLE public.lbs_locations OWNER TO camera_migrator;
ALTER TABLE public.camera_deletion_tombstones OWNER TO camera_migrator;
ALTER TABLE public.camera_create_idempotency OWNER TO camera_migrator;
ALTER TABLE public.flyway_camera_schema_history OWNER TO camera_migrator;
ALTER SEQUENCE public.camera_number_seq OWNER TO camera_migrator;

ALTER TABLE public.broadcasts OWNER TO stream_migrator;
ALTER TABLE public.stream_consumer_leases OWNER TO stream_migrator;
ALTER TABLE public.flyway_stream_schema_history OWNER TO stream_migrator;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO identity_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO identity_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA identity TO identity_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE
  ON TABLE public.cameras, public.camera_categories, public.lbs_locations,
    public.camera_deletion_tombstones, public.camera_create_idempotency
  TO camera_runtime;
GRANT USAGE, SELECT ON SEQUENCE public.camera_number_seq TO camera_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE
  ON TABLE public.broadcasts, public.stream_consumer_leases
  TO stream_runtime;
