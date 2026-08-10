-- =============================================================================
-- V2__init_customer_schema.sql
-- Schéma: customer
--
-- Byznysová data zákazníků servisu. Mezischémové FK mířící na
-- security.users(id) jsou záměrné. Závisí na V1 (schéma security).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS customer;
SET search_path TO customer;

-- =============================================================================
-- Konfigurace fulltextového vyhledávání
-- unaccent odstraňuje diakritiku: „Novak" najde „Novák"
-- Rozšíření patří do public (systémová úroveň); konfigurace do schématu customer.
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE TEXT SEARCH CONFIGURATION customer.czech_simple (COPY = simple);
ALTER TEXT SEARCH CONFIGURATION customer.czech_simple
    ALTER MAPPING FOR hword, hword_part, word
    WITH public.unaccent, simple;

-- =============================================================================
-- ENUM typy
-- =============================================================================
CREATE TYPE customer_type   AS ENUM ('INDIVIDUAL', 'COMPANY');
CREATE TYPE address_type    AS ENUM ('BILLING', 'CONTACT', 'HEADQUARTERS');
CREATE TYPE contact_channel AS ENUM ('EMAIL', 'PHONE', 'SMS', 'PORTAL');

-- =============================================================================
-- 1. CUSTOMERS
-- FK user_id, created_by — mezischémové: security.users(id)
-- =============================================================================
CREATE TABLE customers (
    id                        BIGSERIAL       PRIMARY KEY,
    user_id                   BIGINT          UNIQUE REFERENCES security.users(id) ON DELETE SET NULL,
    customer_type             customer_type   NOT NULL DEFAULT 'INDIVIDUAL',
    customer_number           VARCHAR(20)     NOT NULL,

    first_name                VARCHAR(100),
    last_name                 VARCHAR(100),
    birth_date                DATE,

    company_name              VARCHAR(255),
    ico                       VARCHAR(15),
    dic                       VARCHAR(15),
    legal_form                VARCHAR(100),

    primary_email             VARCHAR(255),
    primary_phone             VARCHAR(30),

    marketing_consent         BOOLEAN         NOT NULL DEFAULT FALSE,
    marketing_consent_at      TIMESTAMPTZ,
    gdpr_consent              BOOLEAN         NOT NULL DEFAULT FALSE,
    gdpr_consent_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    preferred_contact_channel contact_channel DEFAULT 'EMAIL',
    internal_note             TEXT,
    loyalty_points            INTEGER         NOT NULL DEFAULT 0,
    is_active                 BOOLEAN         NOT NULL DEFAULT TRUE,

    created_at                TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by                BIGINT          REFERENCES security.users(id) ON DELETE SET NULL,

    CONSTRAINT uq_customers_number     UNIQUE (customer_number),
    CONSTRAINT uq_customers_ico        UNIQUE (ico),
    CONSTRAINT chk_individual_required CHECK (
        customer_type = 'COMPANY' OR (first_name IS NOT NULL AND last_name IS NOT NULL)
    ),
    CONSTRAINT chk_company_required    CHECK (
        customer_type = 'INDIVIDUAL' OR company_name IS NOT NULL
    ),
    CONSTRAINT chk_customers_email     CHECK (
        primary_email IS NULL OR
        primary_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
    ),
    CONSTRAINT chk_loyalty_points      CHECK (loyalty_points >= 0)
);

COMMENT ON TABLE  customers            IS 'Byznysový profil zákazníka. Oddělen od autentizačních dat (schéma security).';
COMMENT ON COLUMN customers.id         IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customers.user_id    IS 'NULL = zákazník bez portálového účtu. Mezischémový FK -> security.users.id.';
COMMENT ON COLUMN customers.created_by IS 'Mezischémový FK -> security.users.id.';
COMMENT ON COLUMN customers.ico        IS 'IČO bez mezer, unikátní v celém systému.';

-- =============================================================================
-- 2. ADDRESSES
-- =============================================================================
CREATE TABLE addresses (
    id            BIGSERIAL    PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES customer.customers(id) ON DELETE CASCADE,
    address_type  address_type NOT NULL DEFAULT 'CONTACT',
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    street        VARCHAR(255) NOT NULL,
    street_number VARCHAR(20)  NOT NULL,
    city          VARCHAR(100) NOT NULL,
    postal_code   VARCHAR(10)  NOT NULL,
    country_code  CHAR(2)      NOT NULL DEFAULT 'CZ',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_postal_code CHECK (
        country_code != 'CZ' OR postal_code ~ '^\d{3}\s?\d{2}$'
    )
);

COMMENT ON TABLE  addresses             IS 'Adresy zákazníků. Jeden zákazník může mít více adres různých typů.';
COMMENT ON COLUMN addresses.id          IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN addresses.customer_id IS 'FK Long -> customer.customers.id.';

-- Částečný unikátní index: nejvýše jedna výchozí adresa daného typu na zákazníka.
CREATE UNIQUE INDEX uq_addresses_default_per_type
    ON addresses (customer_id, address_type)
    WHERE is_default = TRUE;

-- =============================================================================
-- 3. CONTACT_PERSONS
-- FK user_id — mezischémový: security.users(id)
-- =============================================================================
CREATE TABLE contact_persons (
    id          BIGSERIAL    PRIMARY KEY,
    customer_id BIGINT       NOT NULL REFERENCES customer.customers(id) ON DELETE CASCADE,
    user_id     BIGINT       UNIQUE REFERENCES security.users(id) ON DELETE SET NULL,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    position    VARCHAR(100),
    email       VARCHAR(255),
    phone       VARCHAR(30),
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    note        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_cp_email CHECK (
        email IS NULL OR
        email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
    )
);

COMMENT ON TABLE  contact_persons             IS 'Kontaktní osoby — především pro firemní zákazníky.';
COMMENT ON COLUMN contact_persons.id          IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN contact_persons.customer_id IS 'FK Long -> customer.customers.id.';
COMMENT ON COLUMN contact_persons.user_id     IS 'Mezischémový FK -> security.users.id. Vyplněn, když má kontaktní osoba vlastní přístup do portálu.';

-- Nejvýše jedna primární kontaktní osoba na zákazníka.
CREATE UNIQUE INDEX uq_contact_persons_primary
    ON contact_persons (customer_id)
    WHERE is_primary = TRUE;

-- =============================================================================
-- 4. CUSTOMER_COMMUNICATIONS
-- FK handled_by — mezischémový: security.users(id)
-- =============================================================================
CREATE TABLE customer_communications (
    id              BIGSERIAL       PRIMARY KEY,
    customer_id     BIGINT          NOT NULL REFERENCES customer.customers(id) ON DELETE CASCADE,
    channel         contact_channel NOT NULL,
    direction       VARCHAR(10)     NOT NULL,
    subject         VARCHAR(255),
    body            TEXT,
    handled_by      BIGINT          REFERENCES security.users(id) ON DELETE SET NULL,
    communicated_at TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_direction CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

COMMENT ON TABLE  customer_communications            IS 'Záznam komunikace se zákazníky.';
COMMENT ON COLUMN customer_communications.id         IS 'BIGSERIAL — Java Long.';
COMMENT ON COLUMN customer_communications.handled_by IS 'Mezischémový FK -> security.users.id.';

-- =============================================================================
-- Indexy
-- =============================================================================
CREATE INDEX idx_customers_user_id      ON customers (user_id);
CREATE INDEX idx_customers_type         ON customers (customer_type);
CREATE INDEX idx_customers_last_name    ON customers (last_name)     WHERE customer_type = 'INDIVIDUAL';
CREATE INDEX idx_customers_company_name ON customers (company_name)  WHERE customer_type = 'COMPANY';
CREATE INDEX idx_customers_ico          ON customers (ico)           WHERE ico IS NOT NULL;
CREATE INDEX idx_customers_email        ON customers (primary_email) WHERE primary_email IS NOT NULL;
CREATE INDEX idx_customers_active       ON customers (is_active);

CREATE INDEX idx_customers_fts ON customers USING GIN (
    to_tsvector('customer.czech_simple',
        COALESCE(first_name,   '') || ' ' ||
        COALESCE(last_name,    '') || ' ' ||
        COALESCE(company_name, '') || ' ' ||
        COALESCE(ico,          '')
    )
);

CREATE INDEX idx_addresses_customer_id    ON addresses (customer_id);
CREATE INDEX idx_contact_persons_customer ON contact_persons (customer_id);
CREATE INDEX idx_comm_customer_id         ON customer_communications (customer_id);
CREATE INDEX idx_comm_date                ON customer_communications (communicated_at DESC);

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION customer.fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customers_updated_at
    BEFORE UPDATE ON customers FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_addresses_updated_at
    BEFORE UPDATE ON addresses FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

CREATE TRIGGER trg_contact_persons_updated_at
    BEFORE UPDATE ON contact_persons FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();
