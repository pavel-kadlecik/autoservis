-- =============================================================================
-- V32__v_invoice_price_totals.sql
-- Schéma: billing
-- Dopočtené souhrny faktury (počítá se z položek, neukládá se).
-- Závisí na V14 (invoices, invoice_items).
-- =============================================================================

CREATE VIEW billing.v_invoice_price_totals AS
SELECT
    invoice_id,
    COALESCE(SUM(line_net),            0) AS total_net,    -- základ daně
    COALESCE(SUM(line_vat),            0) AS total_vat,    -- samotné DPH
    COALESCE(SUM(line_net + line_vat), 0) AS total_gross   -- celkem = základ + daň (sedí na haléř)
FROM (
         SELECT
             ii.invoice_id,
             ROUND(ii.quantity * ii.unit_price, 2)                       AS line_net,
             ROUND(ii.quantity * ii.unit_price * ii.vat_rate / 100.0, 2) AS line_vat
         FROM billing.invoice_items ii
     ) p
GROUP BY invoice_id;
