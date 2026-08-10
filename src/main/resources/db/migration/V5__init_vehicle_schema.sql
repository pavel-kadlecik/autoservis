-- =============================================================================
-- V5__init_vehicle_schema.sql
-- Schéma: vehicle
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS vehicle;

-- =============================================================================
-- ENUM typy
-- =============================================================================
CREATE TYPE vehicle.fuel_type AS ENUM (
    'PETROL', 'DIESEL', 'LPG', 'CNG',
    'ELECTRIC', 'HYBRID_PETROL', 'HYBRID_DIESEL',
    'HYDROGEN', 'OTHER'
);

CREATE TYPE vehicle.transmission_type AS ENUM (
    'MANUAL', 'AUTOMATIC', 'SEMI_AUTOMATIC', 'CVT', 'DCT'
);

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION vehicle.fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- Tabulka: vehicles
-- =============================================================================
CREATE TABLE vehicle.vehicles (
    id                      BIGSERIAL                 PRIMARY KEY,
    customer_id             BIGINT                    NOT NULL,
    vin                     VARCHAR(17)               NOT NULL,
    license_plate           VARCHAR(15),
    brand                   VARCHAR(100)              NOT NULL,
    model                   VARCHAR(100)              NOT NULL,
    year_of_manufacture     SMALLINT,
    first_registration_date DATE,
    fuel_type               vehicle.fuel_type         NOT NULL,
    transmission            vehicle.transmission_type,
    engine_displacement_ccm INTEGER,
    engine_power_kw         SMALLINT,
    color                   VARCHAR(50),
    current_mileage_km      INTEGER,
    internal_note           TEXT,
    is_active               BOOLEAN                   NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    created_by              BIGINT,

    CONSTRAINT uq_vehicles_vin
        UNIQUE (vin),

    CONSTRAINT fk_vehicles_customer
        FOREIGN KEY (customer_id) REFERENCES customer.customers(id)
        ON UPDATE CASCADE ON DELETE RESTRICT,

    CONSTRAINT fk_vehicles_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id)
        ON UPDATE CASCADE ON DELETE SET NULL,

    CONSTRAINT chk_vehicles_vin_format
        CHECK (vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),

    CONSTRAINT chk_vehicles_year
        CHECK (year_of_manufacture IS NULL OR (
            year_of_manufacture >= 1885 AND
            year_of_manufacture <= EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER + 1
        )),

    CONSTRAINT chk_vehicles_displacement
        CHECK (engine_displacement_ccm IS NULL OR (
            engine_displacement_ccm >= 50 AND engine_displacement_ccm <= 10000
        )),

    CONSTRAINT chk_vehicles_power
        CHECK (engine_power_kw IS NULL OR (
            engine_power_kw >= 1 AND engine_power_kw <= 2000
        )),

    CONSTRAINT chk_vehicles_mileage
        CHECK (current_mileage_km IS NULL OR current_mileage_km >= 0)
);

CREATE TRIGGER trg_vehicles_updated_at
    BEFORE UPDATE ON vehicle.vehicles
    FOR EACH ROW
    EXECUTE FUNCTION vehicle.fn_set_updated_at();
