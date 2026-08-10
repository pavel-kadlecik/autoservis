-- =============================================================================
-- V20__init_vehicle_mileage_history.sql
-- Editovatelný deník záznamů tachometru (fáze 3). Bez auditu, bez soft-delete.
-- vehicles.current_mileage_km = denormalizovaná cache posledního záznamu,
-- udržovaná triggerem. Samotné DDL tabulky vehicles se nemění.
-- =============================================================================

CREATE TYPE vehicle.mileage_source AS ENUM (
    'SERVICE', 'CUSTOMER', 'INITIAL', 'OTHER'
);

CREATE TABLE vehicle.mileage_history (
                                         id             BIGSERIAL              PRIMARY KEY,
                                         vehicle_id     BIGINT                 NOT NULL,
                                         mileage_km     INTEGER                NOT NULL,
                                         recorded_date  DATE                   NOT NULL DEFAULT CURRENT_DATE,
                                         source         vehicle.mileage_source NOT NULL DEFAULT 'OTHER',
                                         note           TEXT,
                                         created_at     TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
                                         created_by     BIGINT,

                                         CONSTRAINT fk_mileage_history_vehicle
                                             FOREIGN KEY (vehicle_id) REFERENCES vehicle.vehicles(id)
                                                 ON UPDATE CASCADE ON DELETE CASCADE,
                                         CONSTRAINT fk_mileage_history_created_by
                                             FOREIGN KEY (created_by) REFERENCES security.users(id)
                                                 ON UPDATE CASCADE ON DELETE SET NULL,
                                         CONSTRAINT chk_mileage_history_km
                                             CHECK (mileage_km >= 0 AND mileage_km <= 9999999),
                                         CONSTRAINT chk_mileage_history_recorded_date
                                             CHECK (recorded_date <= CURRENT_DATE)
);

CREATE INDEX idx_mileage_history_latest
    ON vehicle.mileage_history (vehicle_id, recorded_date DESC, id DESC);

-- Backfill PŘED vytvořením triggeru — jinak by každý seed řádek spustil
-- synchronizační trigger, který UPDATEuje vehicles a zbytečně jim posouvá updated_at.
INSERT INTO vehicle.mileage_history (vehicle_id, mileage_km, recorded_date, source, note)
SELECT id, current_mileage_km, created_at::date, 'INITIAL',
       'Initial reading migrated from vehicles.current_mileage_km'
FROM vehicle.vehicles
WHERE current_mileage_km IS NOT NULL;

-- Synchronizace cache na poslední záznam. Plný přepočet ošetří i UPDATE/DELETE.
CREATE OR REPLACE FUNCTION vehicle.fn_sync_current_mileage()
RETURNS TRIGGER AS $$
DECLARE
v_id BIGINT := COALESCE(NEW.vehicle_id, OLD.vehicle_id);
BEGIN
UPDATE vehicle.vehicles v
SET current_mileage_km = (
    SELECT m.mileage_km
    FROM vehicle.mileage_history m
    WHERE m.vehicle_id = v_id
    ORDER BY m.recorded_date DESC, m.id DESC
    LIMIT 1
    )
WHERE v.id = v_id;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_mileage_history_sync_current
    AFTER INSERT OR UPDATE OR DELETE ON vehicle.mileage_history
    FOR EACH ROW
    EXECUTE FUNCTION vehicle.fn_sync_current_mileage();