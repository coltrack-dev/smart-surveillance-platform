-- The base camera tables are created by the PostgreSQL bootstrap superuser.
-- Transfer ownership to the Camera migration identity before Flyway starts.
ALTER TABLE public.cameras OWNER TO camera_migrator;
ALTER TABLE public.camera_categories OWNER TO camera_migrator;
ALTER TABLE public.lbs_locations OWNER TO camera_migrator;
ALTER SEQUENCE public.camera_number_seq OWNER TO camera_migrator;

GRANT SELECT, INSERT, UPDATE, DELETE
  ON TABLE public.cameras, public.camera_categories, public.lbs_locations
  TO camera_runtime;
GRANT USAGE, SELECT ON SEQUENCE public.camera_number_seq TO camera_runtime;
