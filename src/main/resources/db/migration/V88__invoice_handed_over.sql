-- ============================================================================
-- V88 — předání faktury zákazníkovi
-- ============================================================================
-- Vystavenou fakturu nešlo smazat vůbec: opravovala se výhradně dobropisem
-- (§42/§45 ZDPH, audit KN-1). Ten důvod ale platí jen pro doklad, který se
-- k zákazníkovi opravdu dostal — a to aplikace nevěděla. Neevidovala odeslání
-- ani předání a fakturu sama neposílá; věděla jen, že někdo klikl na „Vystavit",
-- což může být i překlep starý deset vteřin. Za každý takový překlep pak
-- v evidenci ležela dvojice dokladů dokazující, že se někdo upsal.
--
-- Rozhodnutí uživatele 2026-08-07: zavést, co aplikaci chybělo — příznak
-- předání. Vystavení tím přestává znamenat předání. Nepředanou a nezaplacenou
-- fakturu lze smazat; jakmile je předaná, opravuje se dobropisem jako dřív.
--
-- PROČ SLOUPEC A NE STAV: předání je nezávislé na tom, jestli je faktura
-- zaplacená, takže automat DRAFT → ISSUED → PAID zůstává beze změny. Nový stav
-- by musel existovat ve dvou variantách (předaná nezaplacená, předaná zaplacená)
-- a rozbil by lineární řadu.
--
-- BACKFILL: existující vystavené i zaplacené faktury se považují za PŘEDANÉ.
-- Vznikly v době, kdy vystavení předání znamenalo, takže opačná domněnka by
-- zpětně odemkla mazání dokladů, které zákazníci dávno mají. Razítkuje se
-- `issue_date`, resp. `created_at` — přesný okamžik předání u nich nikdo nezná
-- a vymýšlet „teď" by tvrdilo, že se předaly dnes.
-- ============================================================================

ALTER TABLE billing.invoices
    ADD COLUMN handed_over_at TIMESTAMPTZ,
    ADD COLUMN handed_over_by BIGINT,
    ADD CONSTRAINT fk_invoices_handed_over_by
        FOREIGN KEY (handed_over_by) REFERENCES security.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN billing.invoices.handed_over_at IS
    'Kdy obsluha potvrdila, že doklad dostal zákazník. NULL = nepředáno, takže '
    'fakturu lze ještě smazat. Vystavení tenhle příznak NENASTAVUJE (V88).';
COMMENT ON COLUMN billing.invoices.handed_over_by IS
    'Kdo předání potvrdil; SET NULL po smazání uživatele (audit přežije účet).';

-- Historické doklady: vystaveno = předáno (viz komentář výše).
UPDATE billing.invoices
SET handed_over_at = COALESCE(issue_date::TIMESTAMPTZ, created_at)
WHERE status IN ('ISSUED', 'PAID')
  AND handed_over_at IS NULL;

CREATE INDEX idx_invoices_handed_over ON billing.invoices (handed_over_at)
    WHERE handed_over_at IS NULL;
