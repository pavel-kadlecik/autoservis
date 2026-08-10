-- =============================================================================
-- V36__invoice_vehicle_license_plate_snapshot.sql
-- Schéma: billing
--
-- Přidává na fakturu zmražený snapshot SPZ vozidla (license plate).
--
-- Proč jen SPZ a ne celé vozidlo:
--   VIN a značka+model jsou NEMĚNNÉ → čtou se živě přes order → vehicle
--     (žádný snapshot netřeba, JOIN vždy vrátí stejnou hodnotu).
--   SPZ se ale MĚNÍ (přeregistrace) → musí se zmrazit k datu vystavení.
--
-- Sloupec je NULLABLE — vozidlo nemusí mít SPZ (vehicle.license_plate je také
-- nullable u nepřihlášených vozidel).
--
-- Závisí na: V14 (billing.invoices), "order".orders, vehicle.vehicles.
-- =============================================================================

ALTER TABLE billing.invoices
    ADD COLUMN vehicle_license_plate_snapshot VARCHAR(20);

COMMENT ON COLUMN billing.invoices.vehicle_license_plate_snapshot IS
    'Zmražená SPZ fakturovaného vozidla k datu vystavení. NULL, pokud vozidlo SPZ nemělo. VIN / značka / model se čtou živě přes zakázku.';

-- =============================================================================
-- Backfill: SPZ z vozidla, ke kterému se dostaneme přes zakázku faktury.
-- Faktury, jejichž zakázka nemá vozidlo (nebo vozidlo nemá SPZ), zůstanou NULL.
-- =============================================================================
UPDATE billing.invoices AS i
SET vehicle_license_plate_snapshot = v.license_plate
FROM "order".orders AS o
JOIN vehicle.vehicles AS v ON v.id = o.vehicle_id
WHERE o.id = i.order_id;
