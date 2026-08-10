-- =============================================================================
-- V6__init_order_schema.sql
-- Schéma: order (v uvozovkách — rezervované klíčové slovo SQL)
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS "order";

-- =============================================================================
-- ENUM typy
-- =============================================================================
CREATE TYPE "order".order_status AS ENUM (
    'RECEIVED', 'DIAGNOSIS', 'WAITING_FOR_PARTS',
    'IN_PROGRESS', 'READY_FOR_PICKUP', 'COMPLETED', 'CANCELLED'
);

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION "order".fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- Tabulka: orders
-- =============================================================================
CREATE TABLE "order".orders (
    id                      BIGSERIAL             PRIMARY KEY,
    order_number            VARCHAR               NOT NULL,
    customer_id             BIGINT                NOT NULL,
    vehicle_id              BIGINT                NOT NULL,
    status                  "order".order_status  NOT NULL DEFAULT 'RECEIVED',
    description             TEXT                  NOT NULL,
    internal_note           TEXT,
    estimated_completion_at TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    estimated_price         NUMERIC,
    final_price             NUMERIC,
    is_active               BOOLEAN               NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    created_by              BIGINT,

    CONSTRAINT uq_orders_number
        UNIQUE (order_number),

    CONSTRAINT orders_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES customer.customers(id),

    CONSTRAINT orders_vehicle_id_fkey
        FOREIGN KEY (vehicle_id) REFERENCES vehicle.vehicles(id),

    CONSTRAINT orders_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES security.users(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_orders_price
        CHECK (estimated_price >= 0 AND final_price >= 0)
);

CREATE TRIGGER trg_orders_updated_at
    BEFORE UPDATE ON "order".orders
    FOR EACH ROW
    EXECUTE FUNCTION "order".fn_set_updated_at();
