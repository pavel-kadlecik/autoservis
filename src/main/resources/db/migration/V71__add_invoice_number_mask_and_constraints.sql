-- =============================================================================
-- V71__add_invoice_number_mask_and_constraints.sql
-- Schéma: billing
--
-- Číslování faktur podle konfigurovatelné masky (číselná řada à la iDoklad/
-- Fakturoid). Číslo faktury nově vzniká v APLIKACI už při založení konceptu:
-- uživatel ho vidí předvyplněné v dialogu a může ho změnit. Generátor podle
-- masky proto z DB odchází (maska se v plpgsql parsovat nebude) a integritu,
-- kterou dosud držel trigger z V49, přebírají deklarativní constrainty:
--
--   1) company_profile dostává nastavení řady: přepínač invoice_number_auto
--      a masku invoice_number_mask (tokeny {RRRR} {RR} {MM} {N..N}).
--      Default masky odpovídá dosavadnímu formátu YYYYMM###.
--   2) Koncepty bez čísla se dorovnají starým formátem (po zrušení triggeru
--      by je nešlo vystavit a UI pro doplnění čísla konceptu neexistuje).
--   3) invoice_number: VARCHAR(20), nesmí být prázdný/bílé znaky, vystavený
--      či zaplacený doklad ho musí mít a po vystavení je neměnný (guard
--      trigger — aplikace to sice nedovolí, ale daňový doklad si zaslouží
--      garanci v DB).
--   4) variable_symbol: VARCHAR(10), jen číslice (bankovní standard VS;
--      SPAYD X-VS s jiným obsahem = nevalidní QR platba). Nově se negeneruje
--      z čísla faktury — vyplňuje ho uživatel.
--   5) Starý generátor (trigger + funkce z V49) se ruší.
-- =============================================================================

SET search_path TO billing;

-- --- 1) Nastavení číselné řady -----------------------------------------------

ALTER TABLE billing.company_profile
    ADD COLUMN invoice_number_auto BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN invoice_number_mask VARCHAR(40) NOT NULL DEFAULT '{RRRR}{MM}{NNN}';

COMMENT ON COLUMN billing.company_profile.invoice_number_auto IS
    'Zda se číslo faktury skládá podle masky a předvyplňuje v dialogu. Vypnuto = volný ruční zápis.';
COMMENT ON COLUMN billing.company_profile.invoice_number_mask IS
    'Maska číselné řady faktur; tokeny {RRRR} {RR} {MM} a {N..N} (šířka sekvence), zbytek literály.';

-- --- 2) Backfill konceptů bez čísla (starý formát YYYYMM###) -----------------
-- Navazuje na nejvyšší existující pořadí v měsíci daného issue_date, koncepty
-- v jednom měsíci čísluje po sobě podle id.

WITH drafts AS (
    SELECT id,
           TO_CHAR(COALESCE(issue_date, CURRENT_DATE), 'YYYYMM') AS ym,
           ROW_NUMBER() OVER (
               PARTITION BY TO_CHAR(COALESCE(issue_date, CURRENT_DATE), 'YYYYMM')
               ORDER BY id
           ) AS rn
    FROM billing.invoices
    WHERE invoice_number IS NULL
),
base AS (
    SELECT m.ym,
           COALESCE(MAX(CAST(SUBSTRING(i.invoice_number FROM 7) AS INTEGER)), 0) AS max_seq
    FROM (SELECT DISTINCT ym FROM drafts) m
    LEFT JOIN billing.invoices i
           ON i.invoice_number ~ ('^' || m.ym || '[0-9]{3}$')
    GROUP BY m.ym
)
UPDATE billing.invoices inv
SET invoice_number = d.ym || LPAD((b.max_seq + d.rn)::TEXT, 3, '0')
FROM drafts d
JOIN base b ON b.ym = d.ym
WHERE inv.id = d.id;

-- --- 3) Konec DB generátoru z V49 --------------------------------------------
-- Musí odejít PŘED změnou typu sloupce: WHEN klauzule triggeru referencuje
-- invoice_number a Postgres změnu typu takového sloupce odmítá
-- („cannot alter type of a column used in a trigger definition").

DROP TRIGGER trg_invoices_generate_number ON billing.invoices;
DROP FUNCTION billing.fn_generate_invoice_number();

-- --- 4) Integrita čísla faktury ----------------------------------------------

ALTER TABLE billing.invoices
    ALTER COLUMN invoice_number TYPE VARCHAR(20);

ALTER TABLE billing.invoices
    ADD CONSTRAINT chk_invoice_number_not_blank
        CHECK (invoice_number IS NULL OR btrim(invoice_number) <> ''),
    ADD CONSTRAINT chk_invoice_issued_has_number
        CHECK (status NOT IN ('ISSUED'::billing.invoice_status, 'PAID'::billing.invoice_status)
               OR invoice_number IS NOT NULL);

-- Neměnnost čísla po vystavení — náhrada za aplikační WHERE status='DRAFT'
-- na úrovni DB. Přechod DRAFT→ISSUED číslo nemění (vzniklo při založení),
-- takže guard hlídá jen pokusy přepsat číslo už vystaveného dokladu.
CREATE OR REPLACE FUNCTION billing.fn_forbid_invoice_number_change()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Číslo faktury % je po vystavení neměnné (id=%)',
        OLD.invoice_number, OLD.id;
END;
$$;

CREATE TRIGGER trg_invoices_number_immutable
    BEFORE UPDATE ON billing.invoices
    FOR EACH ROW
    WHEN (OLD.status <> 'DRAFT'::billing.invoice_status
          AND NEW.invoice_number IS DISTINCT FROM OLD.invoice_number)
    EXECUTE FUNCTION billing.fn_forbid_invoice_number_change();

-- --- 5) Variabilní symbol: jen číslice, max 10 -------------------------------

ALTER TABLE billing.invoices
    ALTER COLUMN variable_symbol TYPE VARCHAR(10);

ALTER TABLE billing.invoices
    ADD CONSTRAINT chk_invoice_variable_symbol_digits
        CHECK (variable_symbol IS NULL OR variable_symbol ~ '^[0-9]{1,10}$');
