-- =============================================================================
-- V35__company_profile_and_supplier_backfill.sql
-- Schéma: billing
--
-- Zavádí billing.company_profile — jednořádkovou tabulku s identitou VYSTAVITELE
-- (tvé firmy). Tahle identita se při vystavení faktury zmrazí do invoice_party
-- jako řádek s rolí SUPPLIER — stejný princip jako u odběratele, jen zdroj dat
-- je vlastní firma.
--
-- Součástí je i bankovní spojení (tuzemský účet + IBAN/SWIFT pro zahraniční
-- platby), které se snapshotuje na fakturu spolu s ostatní identitou dodavatele.
--
-- Závisí na: V14 (billing.fn_set_updated_at), V34 (billing.invoice_party).
--
-- !!! DŮLEŽITÉ !!!
--   Seed níže vkládá ZÁSTUPNÉ hodnoty. Než začneš vystavovat reálné faktury,
--   nahraď je skutečnými údaji firmy, např.:
--
--     UPDATE billing.company_profile SET
--         name = 'Autoservis XY s.r.o.', ico = '12345678', dic = 'CZ12345678',
--         street = 'Dílenská', street_number = '5', city = 'Ostrava',
--         postal_code = '70200', country_code = 'CZ',
--         bank_account = '123456789/0800',
--         iban = 'CZ6508000000000123456789', swift = 'GIBACZPX'
--     WHERE id = 1;
--
--   Nové faktury berou dodavatele z aktuálního stavu této tabulky v okamžiku
--   vystavení, takže po úpravě údajů budou správné.
-- =============================================================================

-- =============================================================================
-- Tabulka: company_profile (jediný řádek, vynuceno constraintem CHECK id = 1)
-- =============================================================================
CREATE TABLE billing.company_profile (
    id            INTEGER      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name          VARCHAR(255) NOT NULL,
    ico           VARCHAR(15),
    dic           VARCHAR(15),
    street        VARCHAR(255),
    street_number VARCHAR(20),
    city          VARCHAR(100),
    postal_code   VARCHAR(10),
    country_code  CHAR(2)      NOT NULL DEFAULT 'CZ',
    bank_account  VARCHAR(34),
    iban          VARCHAR(34),
    swift         VARCHAR(11),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  billing.company_profile              IS 'Jednořádková tabulka s identitou vystavující firmy (dodavatele). Snapshotuje se na každou fakturu v okamžiku vystavení.';
COMMENT ON COLUMN billing.company_profile.bank_account IS 'Tuzemské číslo účtu, např. 123456789/0800.';
COMMENT ON COLUMN billing.company_profile.iban         IS 'IBAN pro zahraniční platby. NULL, pokud se nepoužívá.';
COMMENT ON COLUMN billing.company_profile.swift        IS 'BIC/SWIFT banky pro zahraniční platby. NULL, pokud se nepoužívá.';

CREATE TRIGGER trg_company_profile_updated_at
    BEFORE UPDATE ON billing.company_profile
    FOR EACH ROW
    EXECUTE FUNCTION billing.fn_set_updated_at();

-- =============================================================================
-- Seed jediného řádku — ZÁSTUPNÉ hodnoty, nahraď skutečnými (viz hlavička).
-- =============================================================================
INSERT INTO billing.company_profile (
    id, name, ico, dic,
    street, street_number, city, postal_code, country_code,
    bank_account, iban, swift
)
VALUES (
    1, 'DOPLŇTE NÁZEV FIRMY', NULL, NULL,
    NULL, NULL, NULL, NULL, 'CZ',
    NULL, NULL, NULL
);

-- =============================================================================
-- invoice_party: bankovní spojení, aby ho šlo zmrazit u dodavatele.
-- Sloupce jsou nullable — u odběratele (CUSTOMER) zůstávají NULL, plní je
-- jen dodavatel (SUPPLIER).
-- =============================================================================
ALTER TABLE billing.invoice_party
    ADD COLUMN bank_account VARCHAR(34),
    ADD COLUMN iban         VARCHAR(34),
    ADD COLUMN swift        VARCHAR(11);

-- =============================================================================
-- Backfill: SUPPLIER řádek pro každou existující fakturu, která ho ještě nemá.
-- NOT EXISTS respektuje UNIQUE (invoice_id, role) a činí migraci opakovatelně
-- bezpečnou.
-- =============================================================================
INSERT INTO billing.invoice_party (
    invoice_id, role, name, ico, dic,
    street, street_number, city, postal_code, country_code,
    bank_account, iban, swift
)
SELECT
    i.id, 'SUPPLIER', cp.name, cp.ico, cp.dic,
    cp.street, cp.street_number, cp.city, cp.postal_code, cp.country_code,
    cp.bank_account, cp.iban, cp.swift
FROM billing.invoices AS i
CROSS JOIN billing.company_profile AS cp
WHERE NOT EXISTS (
    SELECT 1 FROM billing.invoice_party ip
    WHERE ip.invoice_id = i.id AND ip.role = 'SUPPLIER'
);
