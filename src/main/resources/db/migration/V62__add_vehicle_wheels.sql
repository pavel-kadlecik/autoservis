-- =============================================================================
-- V62__add_vehicle_wheels.sql
-- Schéma: vehicle
--
-- Kola (pneu + ráfky per náprava) z registru — denormalizovaná cache na vozidle,
-- stejný vzor jako stk_valid_until (V38): plní ji sync trigger z nejnovějšího
-- registry_snapshots.raw_response (pole "NapravyPneuRafky"), aplikace ji NIKDY
-- nezapisuje. Údaj je jen k zobrazení (needitovatelný, ručně se nezadává).
--
-- Trigger fn_sync_stk_valid_until se jen rozšiřuje (název ponechán — trigger na něj
-- odkazuje; přejmenování by znamenalo zbytečné DROP/CREATE triggeru). Nově plní
-- z téhož nejnovějšího snapshotu i sloupec wheels.
-- =============================================================================

-- 1) Sloupec — vlastněný sync triggerem, nikdy nezapisovaný aplikací (viz stk_valid_until, V38).
ALTER TABLE vehicle.vehicles ADD COLUMN wheels TEXT;

-- 2) Rozšíření sync funkce: z nejnovějšího snapshotu plní STK i kola.
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
        ),
        wheels = (
            SELECT s.raw_response ->> 'NapravyPneuRafky'
            FROM vehicle.registry_snapshots s
            WHERE s.vehicle_id = v_id
            ORDER BY s.fetched_at DESC, s.id DESC
            LIMIT 1
        )
    WHERE v.id = v_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- 3) Backfill existujících vozidel se snapshotem (trigger se u historických řádků nespustí).
UPDATE vehicle.vehicles v
SET wheels = (
    SELECT s.raw_response ->> 'NapravyPneuRafky'
    FROM vehicle.registry_snapshots s
    WHERE s.vehicle_id = v.id
    ORDER BY s.fetched_at DESC, s.id DESC
    LIMIT 1
);

COMMENT ON COLUMN vehicle.vehicles.wheels IS
    'Pneu/ráfky per náprava z registru (raw_response->>NapravyPneuRafky). Denormalizovaná cache plněná sync triggerem, aplikace ji nezapisuje. Jen k zobrazení.';
