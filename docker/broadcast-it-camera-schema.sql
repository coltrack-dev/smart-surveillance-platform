-- Minimal pre-existing camera schema fixture for the isolated Broadcast test DB.
-- camera-service Flyway V1 then applies its normal credential columns/migration.
CREATE SEQUENCE camera_number_seq START WITH 1;

CREATE TABLE lbs_locations (
    id UUID PRIMARY KEY,
    name VARCHAR(255),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    address VARCHAR(255),
    description VARCHAR(255)
);

CREATE TABLE camera_categories (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(255),
    favorite_category BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE cameras (
    id UUID PRIMARY KEY,
    camera_number INTEGER NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    rtsp_url VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(255) NOT NULL,
    last_heartbeat TIMESTAMPTZ,
    auto_start BOOLEAN NOT NULL DEFAULT FALSE,
    last_error TEXT,
    last_status_changed_at TIMESTAMPTZ,
    lbs_id UUID REFERENCES lbs_locations(id),
    category_id UUID REFERENCES camera_categories(id)
);
