-- =============================================================================
-- V93__add_cash_receipt_number_source.sql
-- Schéma: billing
--
-- Zdroj čísla pokladního dokladu — třetí režim „podle čísla faktury".
-- Majitel servisu čísluje PPD shodně s hrazenou fakturou (přání jeho účetní:
-- párování platby s fakturou je pak zadarmo) a vlastní souvislou řadu PPD
-- nevede — hotově se platí jen některé faktury, takže úplnost pokladny se
-- kontroluje přes řadu faktur, ne přes řadu PPD. Rozhodnutí uživatele
-- 2026-08-09; §11 ZoÚ vlastní řadu nevyžaduje, jen jednoznačné označení.
--
-- Boolean přepínač cash_receipt_number_auto (V92) tři režimy nepojme —
-- nahrazuje ho ENUM:
--   MASK    = číslo skládá aplikace dle masky (dosavadní auto = TRUE)
--   INVOICE = dialog předvyplní číslo hrazené faktury (nový režim)
--   MANUAL  = pole zůstává prázdné, číslo píše obsluha (dosavadní auto = FALSE)
--
-- Hlídání mezer PPD (cash_receipt_gap_check_*) zůstává ve schématu beze změny;
-- v režimu INVOICE ho deaktivuje aplikace (findNumberGaps vrací enabled=false):
-- „díry" v řadě PPD jsou tam faktury zaplacené převodem, ne chyba, a souvislost
-- řady hlídá kontrola mezer faktur (V89).
-- =============================================================================

SET search_path TO billing;

CREATE TYPE billing.cash_receipt_number_source AS ENUM ('MASK', 'INVOICE', 'MANUAL');

ALTER TABLE billing.company_profile
    ADD COLUMN cash_receipt_number_source billing.cash_receipt_number_source
        NOT NULL DEFAULT 'MASK';

COMMENT ON COLUMN billing.company_profile.cash_receipt_number_source IS
    'Zdroj čísla PPD v dialogu vystavení: MASK = návrh dle masky, INVOICE = číslo hrazené '
    'faktury, MANUAL = prázdné pole. Zapsat lze v každém režimu libovolné unikátní číslo.';

-- Převod z boolean přepínače (V92): vypnutý automat = ruční zápis.
UPDATE billing.company_profile
SET cash_receipt_number_source = 'MANUAL'::billing.cash_receipt_number_source
WHERE cash_receipt_number_auto = FALSE;

ALTER TABLE billing.company_profile
    DROP COLUMN cash_receipt_number_auto;
