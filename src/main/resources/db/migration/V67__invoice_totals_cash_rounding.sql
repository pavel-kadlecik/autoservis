-- =============================================================================
-- V67__invoice_totals_cash_rounding.sql
-- Schéma: billing
--
-- Zaokrouhlení hotovostní úhrady na JEDNOM místě (audit 2026-07-30, nález L-9/KN-7).
--
-- Dosud se zaokrouhlovalo až na příjmovém pokladním dokladu
-- (`CashReceiptServiceImpl`: totalGross.setScale(0, HALF_UP)), zatímco faktura dál
-- žádala haléřovou částku a evidence úhrady (`paid_amount`) zapsala ještě třetí
-- hodnotu — nezaokrouhlený `total_gross`. Tři čísla o jedné platbě: v pokladně
-- 6 105 Kč, na faktuře 6 105,23 Kč, v pohledávkách vyrovnáno 6 105,23 Kč.
--
-- Řešení (rozhodnutí uživatele 2026-07-30): zaokrouhlit **na faktuře** a nechat
-- ostatní doklady číst tutéž hodnotu. Výpočet patří sem, do view, protože z něj
-- už dnes čtou všichni konzumenti (detail faktury, PDF, QR platba, PPD, dashboard).
--
-- §36 odst. 5 ZDPH (ve znění od 1. 10. 2021): částka vzniklá zaokrouhlením celkové
-- úplaty na celou korunu se **nezahrnuje do základu daně**. Proto se `total_net`
-- a `total_vat` NEMĚNÍ a rozpis DPH (`v_invoice_vat_summary`) zůstává nedotčen —
-- zaokrouhlení stojí vedle nich jako samostatná položka. Zaokrouhluje se výhradně
-- matematicky na celou korunu; zaokrouhlit „dál" by se muselo danit.
--
-- Proč jen `payment_method = 'CASH'`: u kombinovaných způsobů (CASH_OR_TRANSFER,
-- CASH_OR_CARD) se o formě platby rozhoduje až při úhradě, takže fakturu předem
-- zaokrouhlit nelze. Zaplatí-li takový zákazník nakonec hotově, zaokrouhlí PPD
-- a evidence úhrady převezme jeho částku — rozdíl je legitimní zaokrouhlovací
-- rozdíl dle §36/5.
--
-- Sloupce se PŘIDÁVAJÍ na konec (CREATE OR REPLACE VIEW zachovává pořadí i typy
-- původních sloupců), takže stávající dotazy fungují beze změny.
-- =============================================================================

SET search_path TO billing;

CREATE OR REPLACE VIEW billing.v_invoice_price_totals AS
SELECT
    t.invoice_id,
    t.total_net,     -- základ daně (zaokrouhlením se NEMĚNÍ)
    t.total_vat,     -- samotné DPH (zaokrouhlením se NEMĚNÍ)
    t.total_gross,   -- základ + daň, na haléř
    -- rozdíl ze zaokrouhlení: kladný i záporný, mimo základ daně (§36/5)
    CASE WHEN i.payment_method = 'CASH'
             THEN ROUND(t.total_gross) - t.total_gross
         ELSE 0 END AS rounding,
    -- částka, kterou zákazník skutečně platí — tuhle čtou PDF, QR, PPD i evidence úhrady
    CASE WHEN i.payment_method = 'CASH'
             THEN ROUND(t.total_gross)
         ELSE t.total_gross END AS total_to_pay
FROM (
         SELECT
             ii.invoice_id,
             COALESCE(SUM(ROUND(ii.quantity * ii.unit_price, 2)), 0) AS total_net,
             COALESCE(SUM(ROUND(ii.quantity * ii.unit_price * ii.vat_rate / 100.0, 2)), 0) AS total_vat,
             COALESCE(SUM(ROUND(ii.quantity * ii.unit_price, 2)
                        + ROUND(ii.quantity * ii.unit_price * ii.vat_rate / 100.0, 2)), 0) AS total_gross
         FROM billing.invoice_items ii
         GROUP BY ii.invoice_id
     ) t
JOIN billing.invoices i ON i.id = t.invoice_id;

COMMENT ON VIEW billing.v_invoice_price_totals IS
    'Dopočtené souhrny faktury (neukládají se). rounding/total_to_pay = zaokrouhlení hotovostní úhrady na celé Kč mimo základ daně (§36/5 ZDPH, V67/KN-7); u nehotovostní úhrady je rounding 0 a total_to_pay = total_gross.';
