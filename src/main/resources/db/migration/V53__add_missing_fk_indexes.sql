-- =============================================================================
-- V53__add_missing_fk_indexes.sql
--
-- Indexy na často filtrované FK sloupce (audit N-7). `"order".orders` byla jediná
-- tabulka s FK bez jediného indexu — přitom detail zákazníka i vozidla se dotazuje
-- právě přes tyto sloupce (countOpenByCustomerId/VehicleId, JOINy faktur), a RESTRICT
-- kontroly při deaktivaci jedou bez indexu plným skenem.
-- =============================================================================

CREATE INDEX idx_orders_customer_id       ON "order".orders (customer_id);
CREATE INDEX idx_orders_vehicle_id        ON "order".orders (vehicle_id);
CREATE INDEX idx_vehicles_customer_id     ON vehicle.vehicles (customer_id);
CREATE INDEX idx_invoice_items_order_item ON billing.invoice_items (order_item_id);
