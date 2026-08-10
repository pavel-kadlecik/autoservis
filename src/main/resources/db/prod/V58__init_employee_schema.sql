-- =============================================================================
-- V58__init_employee_schema.sql  (PRODUKČNÍ varianta — umístění db/prod)
-- Schéma: employee
--
-- Produkční „dvojče" demo migrace `db/demo/V58__init_employee_schema.sql`: VYTVÁŘÍ
-- STEJNÉ schéma `employee` (tabulka, trigger, index), ale BEZ seedu 4 demo zaměstnanců.
-- Demo verze seeduje zaměstnance #1 s `user_id = 3` (mechanik z V3) a `created_by = 1`
-- (admin z V3) — v produkci ale V3 neběží (je v db/demo), takže by INSERT spadl na FK
-- `fk_employees_user`. Proto má produkce vlastní, schema-only V58.
--
-- Obě verze mají stejné číslo (V58) a nikdy se nepotkají: dev/test běží s locations
-- db/migration,db/demo (vidí demo V58); produkce s db/migration,db/prod (vidí tuto).
-- Následná V59 (db/migration, všude) přidává `order_items.employee_id` s FK na
-- `employee.employees` — schéma proto musí existovat i v produkci (odsud).
--
-- Závisí na V1 (schéma security — volitelná vazba user_id na login).
-- Pozn.: entanglement DDL+demo v původní V58 je evidován jako tech dluh (viz tech-dluhy.md TD-65).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS employee;

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE (vzor ostatních schémat)
-- =============================================================================
CREATE OR REPLACE FUNCTION employee.fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- Tabulka: employees
-- =============================================================================
CREATE TABLE employee.employees (
    id          BIGSERIAL      PRIMARY KEY,
    user_id     BIGINT,                                  -- nullable (D-5): zaměstnanec ≠ login
    first_name  VARCHAR(100)   NOT NULL,
    last_name   VARCHAR(100)   NOT NULL,
    position    VARCHAR(100),
    hourly_rate NUMERIC(10,2),                           -- náklad práce; předvyplní snapshot na položku (D-3)
    hired_at    DATE           NOT NULL,
    left_at     DATE,                                    -- NULL = stále zaměstnán
    is_active   BOOLEAN        NOT NULL DEFAULT TRUE,     -- soft-delete (D-4)
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by  BIGINT,

    CONSTRAINT fk_employees_user
        FOREIGN KEY (user_id) REFERENCES security.users(id)
        ON UPDATE CASCADE ON DELETE SET NULL,

    CONSTRAINT fk_employees_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id)
        ON UPDATE CASCADE ON DELETE SET NULL,

    CONSTRAINT uq_employees_user
        UNIQUE (user_id),                                -- jeden login = nejvýš jeden zaměstnanec

    CONSTRAINT chk_employees_hourly_rate
        CHECK (hourly_rate IS NULL OR hourly_rate >= 0),

    CONSTRAINT chk_employees_dates
        CHECK (left_at IS NULL OR left_at >= hired_at)
);

CREATE TRIGGER trg_employees_updated_at
    BEFORE UPDATE ON employee.employees
    FOR EACH ROW
    EXECUTE FUNCTION employee.fn_set_updated_at();

CREATE INDEX idx_employees_active ON employee.employees (is_active);
