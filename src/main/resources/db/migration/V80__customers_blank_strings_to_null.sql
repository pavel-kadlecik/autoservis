-- =============================================================================
-- V80: Datová oprava — prázdné řetězce v customer.customers převést na NULL
-- =============================================================================
-- Frontend posílal nevyplněná textová pole jako '' a backend je nenormalizoval,
-- takže full-replace UPDATE (E1.1) zapisoval do DB prázdné řetězce místo NULL.
-- Důsledek na produkci: řádek s ico = '' blokoval editaci všech ostatních
-- zákazníků bez IČO (kontrola duplicity existsByIco('') → DUPLICATE_ICO),
-- protože uq_customers_ico povoluje více NULL, ale '' jen jednou.
--
-- Kód nově normalizuje blank → NULL v CustomerConverter; tato migrace srovnává
-- data, která vznikla před opravou.
--
-- primary_email tu chybí záměrně: '' se do něj nikdy dostat nemohl, CHECK
-- chk_customers_email ho odmítá (proto zakládání zákazníka bez e-mailu padalo
-- na 422 už při INSERTu).

-- Sloupce bez CHECK constraintů — plošně.
UPDATE customer.customers SET ico           = NULL WHERE ico           = '';
UPDATE customer.customers SET dic           = NULL WHERE dic           = '';
UPDATE customer.customers SET legal_form    = NULL WHERE legal_form    = '';
UPDATE customer.customers SET primary_phone = NULL WHERE primary_phone = '';
UPDATE customer.customers SET internal_note = NULL WHERE internal_note = '';

-- Jméno/příjmení smí být NULL jen u firmy (chk_individual_required);
-- u fyzické osoby '' vzniknout nemohlo (validace vyžaduje neprázdné hodnoty).
UPDATE customer.customers SET first_name = NULL
WHERE first_name = '' AND customer_type = 'COMPANY';
UPDATE customer.customers SET last_name = NULL
WHERE last_name = '' AND customer_type = 'COMPANY';

-- Název firmy smí být NULL jen u fyzické osoby (chk_company_required).
UPDATE customer.customers SET company_name = NULL
WHERE company_name = '' AND customer_type = 'INDIVIDUAL';
