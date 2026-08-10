-- =============================================================================
-- V63__order_item_summary_cost.sql
-- Schéma: order
-- Rozšíření souhrnu zakázky o náklad (bez DPH) po kategoriích — podklad pro marži.
-- Marže se počítá na FE: marže = "bez DPH" (tržba) − náklad. DPH je průběžná
-- položka a do marže nevstupuje.
-- Závisí na V25 (v_order_item_priced/summary) a V26 (přejmenování service_* sloupců).
-- CREATE OR REPLACE: zachovává existující sloupce ve stejném pořadí, nové přidává na konec.
-- =============================================================================

CREATE OR REPLACE VIEW "order".v_order_item_summary AS
SELECT
    order_id,

    -- Práce
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'LABOR'), 0)          AS labor_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'LABOR'), 0)          AS labor_gross,

    -- Materiál
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'MATERIAL'), 0)       AS material_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'MATERIAL'), 0)       AS material_gross,

    -- Ostatní služby
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'OTHER_SERVICES'), 0) AS service_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'OTHER_SERVICES'), 0) AS service_gross,

    -- Celkem
    COALESCE(SUM(line_net),   0) AS total_net,
    COALESCE(SUM(line_gross), 0) AS total_gross,

    -- Náklad bez DPH = množství × nákupní cena (nákupní cena může být NULL → 0).
    -- Zaokrouhlení po řádku, shodně s výpočtem line_net.
    COALESCE(SUM(ROUND(quantity * COALESCE(purchase_price, 0), 2)) FILTER (WHERE item_type = 'LABOR'), 0)          AS labor_cost,
    COALESCE(SUM(ROUND(quantity * COALESCE(purchase_price, 0), 2)) FILTER (WHERE item_type = 'MATERIAL'), 0)       AS material_cost,
    COALESCE(SUM(ROUND(quantity * COALESCE(purchase_price, 0), 2)) FILTER (WHERE item_type = 'OTHER_SERVICES'), 0) AS service_cost,
    COALESCE(SUM(ROUND(quantity * COALESCE(purchase_price, 0), 2)), 0)                                             AS total_cost
FROM "order".v_order_item_priced
GROUP BY order_id;
