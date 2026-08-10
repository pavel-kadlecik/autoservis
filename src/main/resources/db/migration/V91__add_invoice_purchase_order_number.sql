-- =============================================================================
-- V91__add_invoice_purchase_order_number.sql
-- Schéma: billing
--
-- Faktura může nést číslo objednávky zákazníka (nákupní objednávka, PO).
--
-- Proč (požadavek zákazníka 2026-08-08):
--   Firemní zákazníci objednávají opravy přes vlastní objednávkový systém
--   a fakturu bez svého čísla objednávky neumí spárovat (účtárna ji vrací).
--   Číslo dodává zákazník, autoservis ho jen opíše — proto volný text bez
--   formátového omezení, jen délkový limit.
--
--   Pojmenování purchase_order_number (ne order_number): „order" je v projektu
--   zakázka autoservisu (order_number_snapshot = ZAK-…) a „appointment" termín
--   v kalendáři. Purchase order je standardní termín pro objednávku odběratele.
--
-- Data: nic se nemaže ani nemění — jen nový nullable sloupec, bezpečné
-- i na běžící produkci. NULL = zákazník číslo objednávky neuvedl.
-- =============================================================================

ALTER TABLE billing.invoices
    ADD COLUMN purchase_order_number VARCHAR(100);

COMMENT ON COLUMN billing.invoices.purchase_order_number IS
    'Číslo objednávky zákazníka — nákupní objednávka / PO (V91). Volný text, '
    'zadává se ručně při vytvoření faktury; NULL = neuvedeno. Tiskne se na '
    'fakturu, aby ji odběratel spároval ve svém systému.';
