-- =============================================================================
-- V92__cash_receipt_number_mask.sql
-- Schéma: billing
--
-- Číslování pokladních dokladů podle konfigurovatelné masky — stejný mechanismus
-- jako u faktur (V71 + V89). Číslo PPD nově skládá APLIKACE při vystavení:
-- uživatel ho vidí předvyplněné v dialogu a může ho změnit. Generátor podle
-- masky proto z DB odchází (maska se v plpgsql parsovat nebude, viz V71) a
-- integritu přebírají deklarativní constrainty:
--
--   1) company_profile dostává nastavení řady: přepínač cash_receipt_number_auto
--      a masku cash_receipt_number_mask (tokeny {RRRR} {RR} {MM} {N..N}).
--      Default masky 'PPD{RRRR}{MM}{NNN}' odpovídá dosavadnímu formátu
--      PPD{YYYYMM}### — řada plynule navazuje.
--   2) Hlídání mezer v řadě (zrcadlo V89): volitelné, s vlastním startovním
--      číslem. U PPD je potřeba od začátku — doklad půjde smazat (rozhodnutí
--      uživatele 2026-08-09) a díru po něm zavírá ruční zápis čísla.
--   3) Starý generátor (trigger + funkce z V57) se ruší.
--   4) receipt_number: VARCHAR(20) (sladění s invoice_number), nesmí být
--      prázdný/bílé znaky; po vystavení je neměnný (guard trigger jako u
--      faktur — storno mění jen status, čísla se nedotýká).
--
-- Backfill není potřeba: receipt_number je NOT NULL od V57, každý existující
-- doklad číslo má.
-- =============================================================================

SET search_path TO billing;

-- --- 1) Nastavení číselné řady -----------------------------------------------

ALTER TABLE billing.company_profile
    ADD COLUMN cash_receipt_number_auto BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN cash_receipt_number_mask VARCHAR(40) NOT NULL DEFAULT 'PPD{RRRR}{MM}{NNN}';

COMMENT ON COLUMN billing.company_profile.cash_receipt_number_auto IS
    'Zda se číslo pokladního dokladu skládá podle masky a předvyplňuje v dialogu. Vypnuto = volný ruční zápis.';
COMMENT ON COLUMN billing.company_profile.cash_receipt_number_mask IS
    'Maska číselné řady pokladních dokladů; tokeny {RRRR} {RR} {MM} a {N..N} (šířka sekvence), zbytek literály.';

-- --- 2) Hlídání mezer v řadě (zrcadlo V89) -----------------------------------

ALTER TABLE billing.company_profile
    ADD COLUMN cash_receipt_gap_check_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cash_receipt_gap_check_from    VARCHAR(20);

COMMENT ON COLUMN billing.company_profile.cash_receipt_gap_check_enabled IS
    'Hlídat mezery v číselné řadě pokladních dokladů a varovat v dialogu vystavení (V92).';
COMMENT ON COLUMN billing.company_profile.cash_receipt_gap_check_from IS
    'Číslo dokladu, od kterého se hlídá; starší se ignorují (typicky data přenesená '
    'z jiného systému). NULL = hlídat celé aktuální období od pořadí 1.';

-- --- 3) Konec DB generátoru z V57 --------------------------------------------
-- Odchází PŘED změnou typu sloupce (stejné pořadí jako V71).

DROP TRIGGER trg_cash_receipts_generate_number ON billing.cash_receipts;
DROP FUNCTION billing.fn_generate_cash_receipt_number();

-- --- 4) Integrita čísla dokladu ----------------------------------------------

ALTER TABLE billing.cash_receipts
    ALTER COLUMN receipt_number TYPE VARCHAR(20);

ALTER TABLE billing.cash_receipts
    ADD CONSTRAINT chk_cash_receipt_number_not_blank
        CHECK (btrim(receipt_number) <> '');

-- Neměnnost čísla po vystavení — PPD nemá koncept, číslo je dané od vzniku.
-- Jediný povolený UPDATE je storno (V68) a to číslo nemění, takže guard hlídá
-- jen pokusy přepsat číslo existujícího dokladu (vzor V71).
CREATE OR REPLACE FUNCTION billing.fn_forbid_cash_receipt_number_change()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Číslo pokladního dokladu % je neměnné (id=%)',
        OLD.receipt_number, OLD.id;
END;
$$;

CREATE TRIGGER trg_cash_receipts_number_immutable
    BEFORE UPDATE ON billing.cash_receipts
    FOR EACH ROW
    WHEN (NEW.receipt_number IS DISTINCT FROM OLD.receipt_number)
    EXECUTE FUNCTION billing.fn_forbid_cash_receipt_number_change();
