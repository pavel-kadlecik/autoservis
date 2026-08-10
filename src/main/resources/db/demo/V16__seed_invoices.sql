-- =============================================================================
-- V16__seed_invoices.sql
-- Seed pro billing.invoices a billing.invoice_items.
-- Faktury se vystavují k dokončeným zakázkám 1, 2 a 3.
-- invoice_number a variable_symbol generuje trigger.
-- =============================================================================

-- Faktura 1 — zakázka 1 (výměna oleje, zákazník 3)
INSERT INTO billing.invoices
    (order_id, customer_id, issue_date, due_date, taxable_supply_date, payment_method, status, note, created_by)
VALUES
    (1, 3, '2025-11-15', '2025-11-29', '2025-11-15', 'CARD', 'PAID', NULL, 1);

INSERT INTO billing.invoice_items
    (invoice_id, order_item_id, name, quantity, unit, unit_price, vat_rate, position)
SELECT
    (SELECT id FROM billing.invoices WHERE order_id = 1),
    oi.id, oi.name, oi.quantity, oi.unit, oi.unit_price, oi.vat_rate, oi.position
FROM "order".order_items oi
WHERE oi.order_id = 1;

-- Faktura 2 — zakázka 2 (oprava brzd, zákazník 1)
INSERT INTO billing.invoices
    (order_id, customer_id, issue_date, due_date, taxable_supply_date, payment_method, status, note, created_by)
VALUES
    (2, 1, '2025-12-01', '2025-12-15', '2025-12-01', 'TRANSFER', 'ISSUED', NULL, 1);

INSERT INTO billing.invoice_items
    (invoice_id, order_item_id, name, quantity, unit, unit_price, vat_rate, position)
SELECT
    (SELECT id FROM billing.invoices WHERE order_id = 2),
    oi.id, oi.name, oi.quantity, oi.unit, oi.unit_price, oi.vat_rate, oi.position
FROM "order".order_items oi
WHERE oi.order_id = 2;

-- Faktura 3 — zakázka 3 (pneuservis, zákazník 2)
INSERT INTO billing.invoices
    (order_id, customer_id, issue_date, due_date, taxable_supply_date, payment_method, status, note, created_by)
VALUES
    (3, 2, '2026-01-10', '2026-01-24', '2026-01-10', 'CASH', 'PAID', NULL, 1);

INSERT INTO billing.invoice_items
    (invoice_id, order_item_id, name, quantity, unit, unit_price, vat_rate, position)
SELECT
    (SELECT id FROM billing.invoices WHERE order_id = 3),
    oi.id, oi.name, oi.quantity, oi.unit, oi.unit_price, oi.vat_rate, oi.position
FROM "order".order_items oi
WHERE oi.order_id = 3;
