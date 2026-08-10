-- =============================================================================
-- V37__v_invoice_vat_summary.sql
-- Schéma: billing
--
-- Rekapitulace DPH po sazbách (počítá se z položek, neukládá se).
-- Zrcadlí zaokrouhlení pohledu v_invoice_price_totals (V32) — zaokrouhlí DPH
-- po řádku, pak sečte — jen navíc seskupuje podle vat_rate. Díky stejnému
-- zaokrouhlení součet rekapitulace vždy sedí s celkovými součty faktury.
--
-- Závisí na: V14 (invoice_items).
-- =============================================================================

CREATE VIEW billing.v_invoice_vat_summary AS
SELECT
    invoice_id,
    vat_rate,
    COALESCE(SUM(line_net),            0) AS base,    -- základ daně za sazbu
    COALESCE(SUM(line_vat),            0) AS vat,     -- výše DPH za sazbu
    COALESCE(SUM(line_net + line_vat), 0) AS total    -- celkem za sazbu
FROM (
         SELECT
             ii.invoice_id,
             ii.vat_rate,
             ROUND(ii.quantity * ii.unit_price, 2)                       AS line_net,
             ROUND(ii.quantity * ii.unit_price * ii.vat_rate / 100.0, 2) AS line_vat
         FROM billing.invoice_items ii
     ) p
GROUP BY invoice_id, vat_rate;
