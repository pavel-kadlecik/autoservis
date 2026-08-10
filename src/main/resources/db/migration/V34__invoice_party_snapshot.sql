-- =============================================================================
-- V34__invoice_party_snapshot.sql
-- Schéma: billing
--
-- Zavádí tabulku billing.invoice_party — zmražený snapshot STRAN faktury
-- (odběratel a dodavatel). Faktura je právní doklad: identita stran musí
-- zamrznout k datu vystavení a nesmí sledovat pozdější změny zákazníka.
--
-- Model "na výšku": jedna faktura má více řádků v invoice_party, rozlišených
-- sloupcem role. Struktura sloupců je společná pro obě strany.
--
-- Závisí na: V14 (billing.invoices),
--             V2  (customer.customers, customer.addresses),
--             V33 (invoices.customer_name_snapshot — přebíráme už zmražené jméno).
--
-- POZNÁMKA K DODAVATELI (role SUPPLIER):
--   Tato migrace plní pouze řádky CUSTOMER, protože jméno/IČO/DIČ/adresu
--   zákazníka umíme dohledat z reálných dat. Identita dodavatele (tvé firmy)
--   zatím nemá v databázi svůj zdroj — vyřeší ji samostatná migrace, jakmile
--   přidáme tabulku s údaji vlastní firmy. Nové faktury budou obě strany
--   vkládat při vystavení v aplikační logice (createFromOrder).
-- =============================================================================

-- =============================================================================
-- ENUM: role strany na faktuře
-- =============================================================================
CREATE TYPE billing.invoice_party_role AS ENUM ('SUPPLIER', 'CUSTOMER');

-- =============================================================================
-- Tabulka: invoice_party
-- Neměnný snapshot — řádky se po vystavení faktury už nemění, proto zde
-- není updated_at ani trigger.
-- =============================================================================
CREATE TABLE billing.invoice_party (
    id            BIGSERIAL                  PRIMARY KEY,
    invoice_id    BIGINT                     NOT NULL,
    role          billing.invoice_party_role NOT NULL,

    name          VARCHAR(255)               NOT NULL,
    ico           VARCHAR(15),
    dic           VARCHAR(15),

    street        VARCHAR(255),
    street_number VARCHAR(20),
    city          VARCHAR(100),
    postal_code   VARCHAR(10),
    country_code  CHAR(2)                    NOT NULL DEFAULT 'CZ',

    created_at    TIMESTAMPTZ                NOT NULL DEFAULT NOW(),

    CONSTRAINT invoice_party_invoice_id_fkey
        FOREIGN KEY (invoice_id) REFERENCES billing.invoices(id) ON DELETE CASCADE,

    -- Každá role smí být na faktuře nejvýš jednou (žádní dva odběratelé).
    CONSTRAINT uq_invoice_party_role UNIQUE (invoice_id, role)
);

CREATE INDEX idx_invoice_party_invoice_id ON billing.invoice_party (invoice_id);

COMMENT ON TABLE  billing.invoice_party      IS 'Zmražený snapshot stran faktury (dodavatel / odběratel). Po vystavení neměnný.';
COMMENT ON COLUMN billing.invoice_party.role IS 'SUPPLIER = vystavitel (naše firma), CUSTOMER = odběratel (příjemce).';
COMMENT ON COLUMN billing.invoice_party.name IS 'Celé jméno / název firmy zmražené k datu vystavení.';
COMMENT ON COLUMN billing.invoice_party.ico  IS 'Registrační číslo (IČO). NULL u fyzických osob.';
COMMENT ON COLUMN billing.invoice_party.dic  IS 'DIČ (daňové identifikační číslo). NULL u neplátců / fyzických osob.';

-- =============================================================================
-- Backfill: CUSTOMER řádek pro každou existující fakturu.
--
-- Jméno bereme z již zmraženého invoices.customer_name_snapshot (V33).
-- IČO/DIČ/adresu bereme z AKTUÁLNÍCH dat zákazníka — historické hodnoty
-- k datu vystavení nejsou nikde uloženy (to je právě důvod, proč snapshot
-- zavádíme; u existujících řádků lepší zdroj nemáme).
--
-- Adresa: preferuje se BILLING, pak výchozí (is_default), pak libovolná.
-- =============================================================================
INSERT INTO billing.invoice_party (
    invoice_id, role, name, ico, dic,
    street, street_number, city, postal_code, country_code
)
SELECT
    i.id,
    'CUSTOMER',
    i.customer_name_snapshot,
    c.ico,
    c.dic,
    addr.street,
    addr.street_number,
    addr.city,
    addr.postal_code,
    COALESCE(addr.country_code, 'CZ')
FROM billing.invoices AS i
JOIN customer.customers AS c ON c.id = i.customer_id
LEFT JOIN LATERAL (
    SELECT a.street, a.street_number, a.city, a.postal_code, a.country_code
    FROM customer.addresses AS a
    WHERE a.customer_id = c.id
    ORDER BY (a.address_type = 'BILLING') DESC, a.is_default DESC, a.id
    LIMIT 1
) AS addr ON TRUE;

-- =============================================================================
-- Denormalizace — záměr napsaný nahlas.
-- Od této chvíle je invoices.customer_name_snapshot (zavedený ve V33) VĚDOMĚ
-- denormalizovaná kopie jména odběratele: zůstává na faktuře pro levné vykreslení
-- seznamu bez JOINu na invoice_party. Zdroj pravdy pro právní doklad je
-- billing.invoice_party (řádek role = 'CUSTOMER'). Obě kopie se zapisují jednou
-- při vystavení a jsou neměnné, takže se nikdy nerozejdou.
-- =============================================================================
COMMENT ON COLUMN billing.invoices.customer_name_snapshot IS
    'Denormalizovaná kopie jména strany CUSTOMER pro levné vykreslení seznamu bez JOINu na invoice_party. Zapisuje se jednou při vystavení, neměnná. Zdrojem pravdy pro právní doklad je billing.invoice_party.';
