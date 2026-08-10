-- =============================================================================
-- V38__init_vehicle_registry_snapshots.sql
-- Snapshoty státního registru vozidel (dataovozidlech.cz, „Datová
-- kostka RSV") — jeden řádek na každé úspěšné API vyhledání vozidla. Ukládá
-- extrahovaná pole STK (technická kontrola) plus kompletní surovou JSON
-- odpověď pro audit a budoucí využití (API má limit 27 požadavků/min).
-- vehicles.stk_valid_until = denormalizovaná cache posledního snapshotu,
-- udržovaná triggerem (stejný vzor jako current_mileage_km ve V20).
-- registry_status je záměrně VARCHAR: množinu hodnot řídí Ministerstvo
-- dopravy a může se změnit bez ohlášení — neznámá hodnota nesmí
-- rozbít INSERT.
-- =============================================================================

CREATE TABLE vehicle.registry_snapshots (
    id                   BIGSERIAL   PRIMARY KEY,
    vehicle_id           BIGINT      NOT NULL,
    stk_valid_until      DATE,
    last_inspection_date DATE,
    registry_status      VARCHAR(100),
    raw_response         JSONB       NOT NULL,
    fetched_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           BIGINT,

    CONSTRAINT fk_registry_snapshots_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle.vehicles(id)
            ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_registry_snapshots_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id)
            ON UPDATE CASCADE ON DELETE SET NULL
);

CREATE INDEX idx_registry_snapshots_latest
    ON vehicle.registry_snapshots (vehicle_id, fetched_at DESC, id DESC);

-- Denormalizovaná cache na tabulce vehicles; vlastní ji synchronizační trigger
-- níže, aplikační INSERT/UPDATE do ní nikdy nezapisují.
ALTER TABLE vehicle.vehicles ADD COLUMN stk_valid_until DATE;

-- Částečný index pro filtr seznamu „končící STK" (jen aktivní vozidla).
CREATE INDEX idx_vehicles_stk_valid_until
    ON vehicle.vehicles (stk_valid_until)
    WHERE is_active = TRUE;

-- Synchronizace cache na poslední snapshot. Plný přepočet ošetří i UPDATE/DELETE.
CREATE OR REPLACE FUNCTION vehicle.fn_sync_stk_valid_until()
RETURNS TRIGGER AS $$
DECLARE
v_id BIGINT := COALESCE(NEW.vehicle_id, OLD.vehicle_id);
BEGIN
UPDATE vehicle.vehicles v
SET stk_valid_until = (
    SELECT s.stk_valid_until
    FROM vehicle.registry_snapshots s
    WHERE s.vehicle_id = v_id
    ORDER BY s.fetched_at DESC, s.id DESC
    LIMIT 1
    )
WHERE v.id = v_id;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_registry_snapshots_sync_stk
    AFTER INSERT OR UPDATE OR DELETE ON vehicle.registry_snapshots
    FOR EACH ROW
    EXECUTE FUNCTION vehicle.fn_sync_stk_valid_until();
