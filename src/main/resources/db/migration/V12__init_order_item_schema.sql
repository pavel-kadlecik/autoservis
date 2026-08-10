-- =============================================================================
-- V12__init_order_item_schema.sql
-- Schéma: order
-- Závisí na V6 (schéma order) a V1 (schéma security).
-- =============================================================================

CREATE TYPE "order".order_item_type AS ENUM (
    'LABOR', 'MATERIAL', 'DIAGNOSTIC', 'TOWING', 'RENTAL', 'OTHER'
);

CREATE TABLE "order".order_items (
    id             BIGSERIAL                   PRIMARY KEY,
    order_id       BIGINT                      NOT NULL REFERENCES "order".orders(id) ON DELETE CASCADE,
    item_type      "order".order_item_type     NOT NULL,
    name           VARCHAR(255)                NOT NULL,
    quantity       NUMERIC(10,2)               NOT NULL,
    unit           VARCHAR(20)                 NOT NULL,
    purchase_price NUMERIC(10,2),
    unit_price     NUMERIC(10,2)               NOT NULL,
    vat_rate       SMALLINT                    NOT NULL DEFAULT 21,
    position       SMALLINT                    NOT NULL DEFAULT 0,
    note           TEXT,
    created_at     TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    created_by     BIGINT REFERENCES security.users(id) ON DELETE SET NULL,

    CONSTRAINT chk_order_items_quantity       CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price     CHECK (unit_price >= 0),
    CONSTRAINT chk_order_items_purchase_price CHECK (purchase_price >= 0),
    CONSTRAINT chk_order_items_vat_rate       CHECK (vat_rate >= 0 AND vat_rate <= 100),
    CONSTRAINT chk_order_items_position       CHECK (position >= 0)
);

CREATE TRIGGER trg_order_items_updated_at
    BEFORE UPDATE ON "order".order_items
    FOR EACH ROW
    EXECUTE FUNCTION "order".fn_set_updated_at();

CREATE INDEX idx_order_items_order_id ON "order".order_items (order_id);
