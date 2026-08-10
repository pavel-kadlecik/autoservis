-- =============================================================================
-- V58__init_employee_schema.sql
-- Schéma: employee
-- Evidence zaměstnanců servisu s hodinovou sazbou (náklad práce), datem
-- nástupu/odchodu a soft-delete. Mechanik se přiřazuje k položce zakázky
-- typu LABOR (viz V59) — historická vazba přežívá i odchod zaměstnance.
-- Závisí na V1 (schéma security — volitelná vazba user_id na login).
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

-- =============================================================================
-- Seed: pár mechaniků. Jeden napojený na login 'mechanic' (user_id = 3),
-- ostatní bez přihlašovacího účtu (D-5).
-- =============================================================================
INSERT INTO employee.employees (id, user_id, first_name, last_name, position, hourly_rate, hired_at, left_at, is_active, created_by)
VALUES
    (1, 3,    'Petr',    'Mechanik',  'Automechanik',         550.00, '2021-03-01', NULL,         TRUE,  1),
    (2, NULL, 'Jan',     'Dvořák',    'Automechanik',         520.00, '2022-09-15', NULL,         TRUE,  1),
    (3, NULL, 'Tomáš',   'Svoboda',   'Diagnostik',           650.00, '2020-01-10', NULL,         TRUE,  1),
    (4, NULL, 'Martin',  'Novák',     'Automechanik junior',  420.00, '2023-06-01', '2025-04-30', FALSE, 1);

SELECT setval('employee.employees_id_seq', (SELECT MAX(id) FROM employee.employees));
