-- =============================================================================
-- V50__invoice_vehicle_full_snapshot.sql
-- Schéma: billing
--
-- Doplňuje na fakturu zmražený snapshot VIN, značky a modelu vozidla (audit K-5).
-- V36 zmrazila jen SPZ s premisou, že „VIN/značka/model jsou neměnné" a čtou se
-- živě přes order → vehicle. Ta premisa ale neplatí: `PUT /vehicles/{id}` VIN,
-- značku i model běžně mění (oprava překlepu, přeřazení vozidla), takže vystavený
-- (i zaplacený) daňový doklad by se zpětně měnil. Právní doklad se měnit nesmí →
-- zmrazíme celé vozidlo, ne jen SPZ.
--
-- Sloupce jsou NULLABLE (vozidlo/hodnota nemusí existovat). Backfill z aktuálních
-- dat vozidla (stejně jako V33/V36 — historická hodnota k datu vystavení není
-- k dispozici, bereme nejlepší dostupnou).
--
-- Závisí na: V14 (billing.invoices), "order".orders, vehicle.vehicles.
-- =============================================================================

ALTER TABLE billing.invoices
    ADD COLUMN vehicle_vin_snapshot   VARCHAR(17),
    ADD COLUMN vehicle_brand_snapshot VARCHAR(100),
    ADD COLUMN vehicle_model_snapshot VARCHAR(100);

COMMENT ON COLUMN billing.invoices.vehicle_vin_snapshot IS
    'Zmražený VIN fakturovaného vozidla k datu vystavení (audit K-5). NULL, pokud není znám.';
COMMENT ON COLUMN billing.invoices.vehicle_brand_snapshot IS
    'Zmražená značka vozidla k datu vystavení (audit K-5).';
COMMENT ON COLUMN billing.invoices.vehicle_model_snapshot IS
    'Zmražený model vozidla k datu vystavení (audit K-5).';

UPDATE billing.invoices AS i
SET vehicle_vin_snapshot   = v.vin,
    vehicle_brand_snapshot = v.brand,
    vehicle_model_snapshot = v.model
FROM "order".orders AS o
JOIN vehicle.vehicles AS v ON v.id = o.vehicle_id
WHERE o.id = i.order_id;
