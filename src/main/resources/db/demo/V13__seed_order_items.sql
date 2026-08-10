-- =============================================================================
-- V13__seed_order_items.sql
-- Seed pro order.order_items.
-- Závisí na V12 (tabulka order_items) a V8 (zakázky musí existovat).
-- =============================================================================

INSERT INTO "order".order_items
(order_id, item_type, name, quantity, unit, purchase_price, unit_price, vat_rate, position, note, created_by)
VALUES
    -- Zakázka 1 — výměna oleje
    (1, 'DIAGNOSTIC',   'Diagnostika před servisem',     1.0,  'hod', 300.00,  500.00,  21, 1, null, 1),
    (1, 'LABOR',        'Výměna motorového oleje',       1.5,  'hod', null,    750.00,  21, 2, null, 1),
    (1, 'MATERIAL',     'Motorový olej Castrol 5W-40',   4.0,  'l',   280.00,  420.00,  21, 3, null, 1),
    (1, 'MATERIAL',     'Olejový filtr Mann W712/75',    1.0,  'ks',  85.00,   150.00,  21, 4, null, 1),

    -- Zakázka 2 — výměna brzd
    (2, 'DIAGNOSTIC',   'Diagnostika brzdového systému', 0.5,  'hod', 300.00,  500.00,  21, 1, null, 1),
    (2, 'MATERIAL',     'Brzdové destičky Bosch přední', 1.0,  'sada',650.00,  950.00,  21, 2, null, 1),
    (2, 'MATERIAL',     'Brzdová kapalina DOT4',         0.5,  'l',   90.00,   160.00,  21, 3, null, 1),
    (2, 'LABOR',        'Výměna předních brzdových des.',2.0,  'hod', null,    1200.00, 21, 4, null, 1),

    -- Zakázka 3 — pneuservis
    (3, 'LABOR',        'Přezutí zimních pneumatik',     1.0,  'hod', null,    600.00,  21, 1, null, 1),
    (3, 'MATERIAL',     'Pneumatika Michelin 205/55 R16',4.0,  'ks',  1800.00, 2400.00, 21, 2, 'Sada 4 ks', 1);
