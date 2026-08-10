-- =============================================================================
-- V30__rename_supplier_identifier_columns.sql
-- Schéma: warehouse
-- Přejmenování a rozšíření identifikačních sloupců dodavatele:
--   ico -> registration_number  VARCHAR(15) -> VARCHAR(30)
--   dic -> vat_id               VARCHAR(15) -> VARCHAR(20)
-- Důvod: názvy nevázané na český/slovenský právní pojem (mezinárodně
-- srozumitelné), delší typ pokryje i zahraniční formáty registračních čísel
-- (např. německé "HRB 247469 B" i s doprovodným textem). Formát se nevaliduje
-- v DB (řeší frontend + tenká kontrola v service vrstvě).
-- Constraint uq_suppliers_ico se přejmenovává, aby název odpovídal sloupci.
-- Bezpečné: nad warehouse.suppliers zatím neexistuje žádná Java/MyBatis vrstva.
-- Závisí na V18.
-- =============================================================================

-- 1) Přejmenování sloupců (constraint uq_suppliers_ico zůstává, ukazuje na nový název)
ALTER TABLE warehouse.suppliers RENAME COLUMN ico TO registration_number;
ALTER TABLE warehouse.suppliers RENAME COLUMN dic TO vat_id;

-- 2) Rozšíření délky
ALTER TABLE warehouse.suppliers ALTER COLUMN registration_number TYPE VARCHAR(30);
ALTER TABLE warehouse.suppliers ALTER COLUMN vat_id              TYPE VARCHAR(20);

-- 3) Sladění názvu unikátního constraintu s novým názvem sloupce
ALTER TABLE warehouse.suppliers RENAME CONSTRAINT uq_suppliers_ico TO uq_suppliers_registration_number;

-- 4) Aktualizace komentářů sloupců
COMMENT ON COLUMN warehouse.suppliers.registration_number IS 'Národní registrační / identifikační číslo firmy (CZ/SK IČO, případně zahraniční ekvivalent). Nepovinné, unikátní (NULL se neduplikuje).';
COMMENT ON COLUMN warehouse.suppliers.vat_id IS 'Daňové / DPH identifikační číslo z faktury (CZ DIČ, SK IČ DPH, případně zahraniční VAT ID). Nepovinné, bez formátové validace v DB.';
