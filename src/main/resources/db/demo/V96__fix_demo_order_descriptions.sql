-- =============================================================================
-- V96__fix_demo_order_descriptions.sql
-- Umístění: db/demo (jen dev/local + test)
--
-- Oprava nekonzistence demo seedů: popisy zakázek 2 a 3 z V8 neodpovídaly
-- jejich položkám z V13 (posun o jednu při psaní seedů):
--   ZAK-2025-0002 „Oprava klimatizace…"      → položky jsou výměna brzd,
--   ZAK-2025-0003 „Výměna brzdových destiček" → položky jsou pneuservis.
-- Fakturační komentáře ve V16 odpovídají položkám, proto se srovnávají POPISY
-- podle položek, ne naopak. Pouze demo data — produkce db/demo nenačítá.
-- updated_at srovná trigger trg_orders_updated_at.
-- =============================================================================

UPDATE "order".orders
   SET description = 'Výměna předních brzdových destiček'
 WHERE order_number = 'ZAK-2025-0002';

UPDATE "order".orders
   SET description = 'Přezutí na zimní pneumatiky, dodání 4 ks pneumatik'
 WHERE order_number = 'ZAK-2025-0003';
