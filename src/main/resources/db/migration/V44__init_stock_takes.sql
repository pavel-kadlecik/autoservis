-- =============================================================================
-- V44__init_stock_takes.sql
-- Schéma: warehouse
-- Inventura (P-5, rozhodnutí R-H v docs/analyza-sklad-2026-07.md):
-- soupis k datu, zadání skutečných stavů a uzavření, které vygeneruje korekce.
-- Manko = záporný ADJUSTMENT po šaržích (FIFO), přebytek = nová šarže
-- v pseudo-příjemce typu STOCK_TAKE (šarže bez příjemky vzniknout nemůže).
-- Závisí na V18 (products, goods_receipts), V39 (chk_receipt_confirmed_complete).
--
-- flyway:noAutoCommit
-- Nová hodnota ENUMu se hned používá v novém CHECKu — PostgreSQL to v jedné
-- transakci neumí, proto explicitní COMMIT (past z V17).
-- =============================================================================

CREATE TYPE warehouse.stock_take_status AS ENUM ('OPEN', 'CLOSED', 'CANCELLED');

ALTER TYPE warehouse.document_type ADD VALUE IF NOT EXISTS 'STOCK_TAKE';

COMMIT;

-- -----------------------------------------------------------------------------
-- 1. Pseudo-příjemka inventurních přebytků nemá dodavatele ani částky
-- -----------------------------------------------------------------------------
-- Inventurní přebytek je nalezené zboží, ne dodávka — nemá dodavatele, číslo
-- dokladu od něj ani fakturované částky. Pro tenhle typ se proto úplnost
-- nevyžaduje; pro faktury a dodací listy platí dál beze změny (V39).
ALTER TABLE warehouse.goods_receipts
    DROP CONSTRAINT chk_receipt_confirmed_complete;

ALTER TABLE warehouse.goods_receipts
    ADD CONSTRAINT chk_receipt_confirmed_complete CHECK (
        status <> 'CONFIRMED'
        OR document_type = 'STOCK_TAKE'
        OR (
            supplier_id    IS NOT NULL AND
            invoice_number IS NOT NULL AND
            subtotal       IS NOT NULL AND
            vat_amount     IS NOT NULL AND
            total_amount   IS NOT NULL
        )
    );

-- -----------------------------------------------------------------------------
-- 2. Hlavička inventury
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.stock_takes (
    id                 BIGSERIAL PRIMARY KEY,
    status             warehouse.stock_take_status NOT NULL DEFAULT 'OPEN',
    note               VARCHAR(500),
    opened_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    opened_by          BIGINT,
    closed_at          TIMESTAMPTZ,
    closed_by          BIGINT,
    surplus_receipt_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_stock_takes_opened_by
        FOREIGN KEY (opened_by) REFERENCES security.users (id) ON DELETE SET NULL,
    CONSTRAINT fk_stock_takes_closed_by
        FOREIGN KEY (closed_by) REFERENCES security.users (id) ON DELETE SET NULL,
    -- RESTRICT: doklad přebytků nesmí zmizet zpod uzavřené inventury
    CONSTRAINT fk_stock_takes_surplus_receipt
        FOREIGN KEY (surplus_receipt_id) REFERENCES warehouse.goods_receipts (id) ON DELETE RESTRICT
);

-- Jen jedna otevřená inventura naráz — dvě souběžné by si korekce přepisovaly.
CREATE UNIQUE INDEX uq_stock_take_single_open
    ON warehouse.stock_takes ((status))
    WHERE status = 'OPEN';

CREATE INDEX idx_stock_takes_status ON warehouse.stock_takes (status);

CREATE TRIGGER trg_stock_takes_updated_at
    BEFORE UPDATE ON warehouse.stock_takes
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON TABLE warehouse.stock_takes IS
    'Inventura: soupis k datu, po uzavření generuje korekční pohyby. Jen jedna OPEN naráz.';
COMMENT ON COLUMN warehouse.stock_takes.surplus_receipt_id IS
    'Pseudo-příjemka typu STOCK_TAKE založená při uzavření pro inventurní přebytky.';

-- -----------------------------------------------------------------------------
-- 3. Položky soupisu
-- -----------------------------------------------------------------------------
CREATE TABLE warehouse.stock_take_items (
    id                 BIGSERIAL PRIMARY KEY,
    stock_take_id      BIGINT NOT NULL,
    product_id         BIGINT NOT NULL,
    expected_quantity  NUMERIC(12,3) NOT NULL,
    counted_quantity   NUMERIC(12,3),
    surplus_unit_price NUMERIC(12,2),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_stock_take_items_take
        FOREIGN KEY (stock_take_id) REFERENCES warehouse.stock_takes (id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_take_items_product
        FOREIGN KEY (product_id) REFERENCES warehouse.products (id) ON DELETE RESTRICT,
    CONSTRAINT uq_stock_take_product UNIQUE (stock_take_id, product_id),
    CONSTRAINT chk_stock_take_counted   CHECK (counted_quantity IS NULL OR counted_quantity >= 0),
    CONSTRAINT chk_stock_take_expected  CHECK (expected_quantity >= 0),
    CONSTRAINT chk_stock_take_price     CHECK (surplus_unit_price IS NULL OR surplus_unit_price >= 0)
);

CREATE INDEX idx_stock_take_items_take ON warehouse.stock_take_items (stock_take_id);

CREATE TRIGGER trg_stock_take_items_updated_at
    BEFORE UPDATE ON warehouse.stock_take_items
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON COLUMN warehouse.stock_take_items.expected_quantity IS
    'Snapshot quantity_on_hand při otevření inventury - jen informativní. Rozdíl se při uzavření počítá proti AKTUÁLNÍMU stavu.';
COMMENT ON COLUMN warehouse.stock_take_items.counted_quantity IS
    'Skutečně napočítané množství. NULL = nepočítáno (negeneruje korekci), není to nula.';
COMMENT ON COLUMN warehouse.stock_take_items.surplus_unit_price IS
    'Nákupní cena pro případný přebytek. Předvyplněná z nejnovější šarže dílu, uživatel může přepsat.';
