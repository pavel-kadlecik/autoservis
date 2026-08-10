-- =============================================================================
-- V14__init_billing_schema.sql
-- Schéma: billing
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS billing;

-- =============================================================================
-- ENUM typy
-- =============================================================================
CREATE TYPE billing.invoice_status AS ENUM (
    'ISSUED', 'PAID', 'CANCELLED'
);

CREATE TYPE billing.payment_method AS ENUM (
    'CARD', 'CASH', 'TRANSFER'
);

-- =============================================================================
-- Tabulka: invoices
-- =============================================================================
CREATE TABLE billing.invoices (
    id                  BIGSERIAL              PRIMARY KEY,
    invoice_number      VARCHAR                NOT NULL,
    order_id            BIGINT                 NOT NULL,
    customer_id         BIGINT                 NOT NULL,
    issue_date          DATE                   NOT NULL DEFAULT CURRENT_DATE,
    due_date            DATE                   NOT NULL,
    taxable_supply_date DATE                   NOT NULL,
    variable_symbol     VARCHAR                NOT NULL,
    constant_symbol     VARCHAR,
    specific_symbol     VARCHAR,
    payment_method      billing.payment_method NOT NULL DEFAULT 'CASH',
    status              billing.invoice_status NOT NULL DEFAULT 'ISSUED',
    note                TEXT,
    created_at          TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    created_by          BIGINT REFERENCES security.users(id) ON DELETE SET NULL,

    CONSTRAINT uq_invoices_order_id   UNIQUE (order_id),
    CONSTRAINT uq_invoice_number      UNIQUE (invoice_number),

    CONSTRAINT invoices_order_id_fkey
        FOREIGN KEY (order_id) REFERENCES "order".orders(id),

    CONSTRAINT invoices_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES customer.customers(id),

    CONSTRAINT chk_due_date
        CHECK (due_date >= issue_date)
);

-- =============================================================================
-- Tabulka: invoice_items
-- =============================================================================
CREATE TABLE billing.invoice_items (
    id            BIGSERIAL     PRIMARY KEY,
    invoice_id    BIGINT        NOT NULL,
    order_item_id BIGINT        NOT NULL,
    name          VARCHAR       NOT NULL,
    quantity      NUMERIC(10,2) NOT NULL,
    unit          VARCHAR(20)   NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    vat_rate      SMALLINT      NOT NULL DEFAULT 21,
    position      SMALLINT      NOT NULL DEFAULT 0,

    CONSTRAINT invoice_items_invoice_id_fkey
        FOREIGN KEY (invoice_id) REFERENCES billing.invoices(id) ON DELETE CASCADE,

    CONSTRAINT invoice_items_order_item_id_fkey
        FOREIGN KEY (order_item_id) REFERENCES "order".order_items(id) ON DELETE RESTRICT,

    CONSTRAINT chk_invoices_items_quantity  CHECK (quantity > 0),
    CONSTRAINT chk_invoices_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_invoices_items_vat_rate  CHECK (vat_rate >= 0 AND vat_rate <= 100),
    CONSTRAINT chk_invoices_items_position  CHECK (position >= 0)
);

-- =============================================================================
-- Indexy
-- =============================================================================
CREATE INDEX idx_invoices_customer_id     ON billing.invoices (customer_id);
CREATE INDEX idx_invoices_status          ON billing.invoices (status);
CREATE INDEX idx_invoice_items_invoice_id ON billing.invoice_items (invoice_id);

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION billing.fn_set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoices_updated_at
    BEFORE UPDATE ON billing.invoices
    FOR EACH ROW
    EXECUTE FUNCTION billing.fn_set_updated_at();
