-- =============================================================================
-- V19__add_vehicle_engine_code.sql
-- Přidává tovární kód motoru do vehicle.vehicles.
-- Servis ho používá k objednávání dílů a hledání olejů/filtrů/postupů.
-- Příklady: „642.980" (Mercedes OM642), „CAXA" (VW 1.8 TSI), „N47D20" (BMW).
-- Nullable: kód při registraci vozidla často není znám.
-- =============================================================================

ALTER TABLE vehicle.vehicles
    ADD COLUMN engine_code VARCHAR(30);

-- Zabraňuje prázdným řetězcům vydávajícím se za hodnotu — „neznámo" zůstává poctivý NULL.
ALTER TABLE vehicle.vehicles
    ADD CONSTRAINT chk_vehicles_engine_code_not_blank
        CHECK (engine_code IS NULL OR length(btrim(engine_code)) > 0);