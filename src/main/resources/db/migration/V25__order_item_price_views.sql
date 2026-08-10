-- =============================================================================
-- V25__order_item_price_views.sql
-- Schéma: order
-- Dopočtené ceny položek a živý souhrn za zakázku (počítá se z dat, neukládá se).
-- Závisí na V12 (order_items) a V24 (3hodnotovém enumu order_item_type).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Pohled 1: ceny jednotlivého řádku
-- -----------------------------------------------------------------------------
CREATE VIEW "order".v_order_item_priced AS
SELECT
    p.*,
    p.line_net + p.line_vat AS line_gross          -- s DPH = základ + daň (sedí na haléř)
FROM (
         SELECT
             oi.*,
             ROUND(oi.quantity * oi.unit_price, 2)                        AS line_net,   -- bez DPH
             ROUND(oi.quantity * oi.unit_price * oi.vat_rate / 100.0, 2)  AS line_vat    -- samotné DPH
         FROM "order".order_items oi
     ) p;

-- -----------------------------------------------------------------------------
-- Pohled 2: souhrn za zakázku, rozdělený podle typu položky
-- -----------------------------------------------------------------------------
CREATE VIEW "order".v_order_item_summary AS
SELECT
    order_id,

    -- Práce
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'LABOR'), 0)          AS labor_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'LABOR'), 0)          AS labor_gross,

    -- Materiál
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'MATERIAL'), 0)       AS material_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'MATERIAL'), 0)       AS material_gross,

    -- Ostatní služby
    COALESCE(SUM(line_net)   FILTER (WHERE item_type = 'OTHER_SERVICES'), 0) AS services_net,
    COALESCE(SUM(line_gross) FILTER (WHERE item_type = 'OTHER_SERVICES'), 0) AS services_gross,

    -- Celkem
    COALESCE(SUM(line_net),   0) AS total_net,
    COALESCE(SUM(line_gross), 0) AS total_gross
FROM "order".v_order_item_priced
GROUP BY order_id;