-- Run by the Streaming PostgreSQL administrator on the `streaming` database.
-- Docker's fresh-volume entrypoint loads this before the base schema. Existing
-- installations run this explicitly during the reviewed role migration.
\getenv identity_runtime_password STREAMING_IDENTITY_RUNTIME_PASSWORD
\getenv identity_migrator_password STREAMING_IDENTITY_MIGRATOR_PASSWORD
\getenv camera_runtime_password STREAMING_CAMERA_RUNTIME_PASSWORD
\getenv camera_migrator_password STREAMING_CAMERA_MIGRATOR_PASSWORD
\getenv stream_runtime_password STREAMING_STREAM_RUNTIME_PASSWORD
\getenv stream_migrator_password STREAMING_STREAM_MIGRATOR_PASSWORD
\getenv websocket_runtime_password STREAMING_WEBSOCKET_RUNTIME_PASSWORD

SELECT format('CREATE ROLE identity_runtime LOGIN PASSWORD %L', :'identity_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'identity_runtime')
\gexec
ALTER ROLE identity_runtime LOGIN PASSWORD :'identity_runtime_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE identity_migrator LOGIN PASSWORD %L', :'identity_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'identity_migrator')
\gexec
ALTER ROLE identity_migrator LOGIN PASSWORD :'identity_migrator_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE camera_runtime LOGIN PASSWORD %L', :'camera_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'camera_runtime')
\gexec
ALTER ROLE camera_runtime LOGIN PASSWORD :'camera_runtime_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE camera_migrator LOGIN PASSWORD %L', :'camera_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'camera_migrator')
\gexec
ALTER ROLE camera_migrator LOGIN PASSWORD :'camera_migrator_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE stream_runtime LOGIN PASSWORD %L', :'stream_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stream_runtime')
\gexec
ALTER ROLE stream_runtime LOGIN PASSWORD :'stream_runtime_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE stream_migrator LOGIN PASSWORD %L', :'stream_migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stream_migrator')
\gexec
ALTER ROLE stream_migrator LOGIN PASSWORD :'stream_migrator_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

SELECT format('CREATE ROLE websocket_runtime LOGIN PASSWORD %L', :'websocket_runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'websocket_runtime')
\gexec
ALTER ROLE websocket_runtime LOGIN PASSWORD :'websocket_runtime_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

REVOKE ALL ON DATABASE streaming FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE streaming FROM PUBLIC;
REVOKE ALL ON DATABASE streaming FROM
  identity_runtime, identity_migrator,
  camera_runtime, camera_migrator,
  stream_runtime, stream_migrator,
  websocket_runtime;
GRANT CONNECT ON DATABASE streaming TO
  identity_runtime, identity_migrator,
  camera_runtime, camera_migrator,
  stream_runtime, stream_migrator,
  websocket_runtime;
-- Identity V1 contains CREATE SCHEMA IF NOT EXISTS identity. PostgreSQL checks
-- database CREATE even when the schema already exists, so this is reserved for
-- the dedicated identity migration identity and never granted to app roles.
GRANT CREATE ON DATABASE streaming TO identity_migrator;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM identity_runtime, camera_runtime, stream_runtime, websocket_runtime;
GRANT USAGE ON SCHEMA public TO camera_runtime, stream_runtime;
GRANT USAGE, CREATE ON SCHEMA public TO camera_migrator, stream_migrator;

CREATE SCHEMA IF NOT EXISTS identity AUTHORIZATION identity_migrator;
ALTER SCHEMA identity OWNER TO identity_migrator;
REVOKE ALL ON SCHEMA identity FROM PUBLIC;
REVOKE ALL ON SCHEMA identity FROM
  identity_runtime, camera_runtime, stream_runtime, websocket_runtime;
GRANT USAGE ON SCHEMA identity TO
  identity_runtime, camera_runtime, stream_runtime, websocket_runtime;

REVOKE ALL ON ALL TABLES IN SCHEMA identity FROM camera_runtime, stream_runtime, websocket_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA identity FROM camera_runtime, stream_runtime, websocket_runtime;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA identity FROM camera_runtime, stream_runtime, websocket_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM identity_runtime, camera_runtime, stream_runtime, websocket_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM identity_runtime, camera_runtime, stream_runtime, websocket_runtime;

DO $$
DECLARE
  object_name TEXT;
BEGIN
  FOREACH object_name IN ARRAY ARRAY[
    'cameras', 'camera_categories', 'lbs_locations',
    'camera_deletion_tombstones', 'camera_create_idempotency'
  ] LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO camera_runtime', object_name);
    END IF;
  END LOOP;
  FOREACH object_name IN ARRAY ARRAY['broadcasts', 'stream_consumer_leases'] LOOP
    IF to_regclass('public.' || object_name) IS NOT NULL THEN
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO stream_runtime', object_name);
    END IF;
  END LOOP;
  IF to_regclass('public.camera_number_seq') IS NOT NULL THEN
    GRANT USAGE, SELECT ON SEQUENCE public.camera_number_seq TO camera_runtime;
  END IF;
END;
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO identity_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO identity_runtime;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA identity FROM identity_runtime;
DO $$
BEGIN
  IF to_regclass('identity.flyway_identity_schema_history') IS NOT NULL THEN
    REVOKE ALL ON TABLE identity.flyway_identity_schema_history FROM identity_runtime;
  END IF;
  IF to_regclass('public.flyway_camera_schema_history') IS NOT NULL THEN
    REVOKE ALL ON TABLE public.flyway_camera_schema_history FROM camera_runtime;
  END IF;
  IF to_regclass('public.flyway_stream_schema_history') IS NOT NULL THEN
    REVOKE ALL ON TABLE public.flyway_stream_schema_history FROM stream_runtime;
  END IF;
END;
$$;

DO $$
BEGIN
  IF to_regprocedure('identity.is_active_tenant(uuid)') IS NOT NULL
     AND to_regprocedure('identity.effective_tenant_quota(uuid,text)') IS NOT NULL THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION identity.is_active_tenant(UUID), identity.effective_tenant_quota(UUID, TEXT) TO camera_runtime, stream_runtime';
  END IF;
  IF to_regprocedure('identity.resolve_tenant_membership(text,text,uuid)') IS NOT NULL
     AND to_regprocedure('identity.resolve_active_role(uuid,uuid)') IS NOT NULL THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION identity.resolve_tenant_membership(TEXT, TEXT, UUID), identity.resolve_active_role(UUID, UUID) TO camera_runtime, stream_runtime, websocket_runtime';
  END IF;
END;
$$;

ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO identity_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
  GRANT USAGE, SELECT ON SEQUENCES TO identity_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
  REVOKE EXECUTE ON FUNCTIONS FROM identity_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA identity
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

ALTER DEFAULT PRIVILEGES FOR ROLE camera_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO camera_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE camera_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO camera_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE stream_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stream_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE stream_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO stream_runtime;
