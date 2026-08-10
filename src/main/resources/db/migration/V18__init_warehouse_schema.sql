-- ============================================================================
-- V18__init_warehouse_schema.sql
--
-- Modul WAREHOUSE (sklad)
--
-- Eviduje sklad náhradních dílů vzniklý z dodavatelských faktur (typicky
-- importovaných z PDF). Udržuje plnou dohledatelnost každé položky na skladě
-- zpět k číslu faktury a číslu objednávky.
--
-- Vrstvy modelu:
--   1. DOKUMENT - goods_receipts / goods_receipt_items  (co a za kolik nám dodavatel vyfakturoval)
--   2. PRODUKT  - products                              (typ dílu, skladová karta)
--   3. POHYB    - stock_movements                       (deník příjmů a výdejů, zdroj pravdy o množství)
--
-- Konvence projektu: BIGSERIAL PK (Long), TIMESTAMPTZ (OffsetDateTime),
-- updated_at přes trigger, ENUM ve vlastnícím schématu, soft-delete, plně
-- kvalifikované názvy, mezischémový FK na security.users.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS warehouse;

-- ----------------------------------------------------------------------------
-- ENUM typy
-- ----------------------------------------------------------------------------

-- Stav příjemky během procesu AI importu PDF. Doklad se nikdy nenaskladní
-- naslepo - po extrakci čeká na lidskou kontrolu.
CREATE TYPE warehouse.receipt_status AS ENUM (
    'PENDING_REVIEW',   -- AI data vytěžila, čeká se na potvrzení mechanikem
    'CONFIRMED',        -- mechanik zkontroloval a potvrdil
    'REJECTED'          -- chybná extrakce, doklad odmítnut
);

-- Typ skladového pohybu. Znaménko množství je vázáno na typ (viz CHECK).
CREATE TYPE warehouse.movement_type AS ENUM (
    'RECEIPT',          -- příjem z dodavatelské faktury (+)
    'ISSUE',            -- výdej na servisní zakázku (-)
    'ADJUSTMENT',       -- inventurní korekce (+/-)
    'RETURN',           -- vratka dodavateli - vadné/špatné díly (-)
    'WRITE_OFF'         -- odpis - zničeno, ztraceno (-)
);

-- Důvod vratky dodavateli. Vyplňuje se jen u pohybů typu RETURN.
CREATE TYPE warehouse.return_reason AS ENUM (
    'DEFECTIVE',         -- vadný díl
    'WRONG_PART',        -- chybně dodaný / objednaný díl
    'DAMAGED_TRANSPORT', -- poškozeno při přepravě
    'SURPLUS',           -- přebytek, není potřeba
    'OTHER'
);

-- ----------------------------------------------------------------------------
-- Triggerové funkce schématu
-- ----------------------------------------------------------------------------

-- Automaticky nastaví updated_at = NOW() před každým UPDATE.
CREATE OR REPLACE FUNCTION warehouse.fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 1. SUPPLIERS - registr dodavatelů
-- ============================================================================
CREATE TABLE warehouse.suppliers (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    ico           VARCHAR(15),
    dic           VARCHAR(15),
    street        VARCHAR(255),
    city          VARCHAR(100),
    postal_code   VARCHAR(10),
    country_code  CHAR(2)      NOT NULL DEFAULT 'CZ',
    bank_account  VARCHAR(50),
    iban          VARCHAR(34),
    swift         VARCHAR(11),
    email         VARCHAR(255),
    phone         VARCHAR(30),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_suppliers_ico UNIQUE (ico)
);

CREATE INDEX idx_suppliers_name ON warehouse.suppliers (name);

CREATE TRIGGER trg_suppliers_updated_at
    BEFORE UPDATE ON warehouse.suppliers
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON TABLE  warehouse.suppliers IS 'Registr dodavatelů náhradních dílů. Deduplikace podle IČO. Cíl vratek a reklamací.';
COMMENT ON COLUMN warehouse.suppliers.is_active IS 'Soft-delete - dodavatel se nikdy nemaže, pouze deaktivuje.';

-- ============================================================================
-- 2. PRODUCTS - skladová karta (typ dílu)
-- ============================================================================
-- Produkt existuje na skladě jednou, i když je přijímán opakovaně z různých
-- faktur. Identifikován přes SKU = katalogové číslo dodavatele.
CREATE TABLE warehouse.products (
    id                BIGSERIAL     PRIMARY KEY,
    sku               VARCHAR(100)  NOT NULL,
    name              VARCHAR(500)  NOT NULL,
    unit              VARCHAR(20)   NOT NULL DEFAULT 'ks',
    default_vat_rate  SMALLINT,
    quantity_on_hand  NUMERIC(12,3) NOT NULL DEFAULT 0,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_products_sku        UNIQUE (sku),
    CONSTRAINT chk_products_qty       CHECK (quantity_on_hand >= 0),
    CONSTRAINT chk_products_vat       CHECK (default_vat_rate IS NULL
                                             OR (default_vat_rate >= 0 AND default_vat_rate <= 100))
);

CREATE INDEX idx_products_name ON warehouse.products (name);

CREATE TRIGGER trg_products_updated_at
    BEFORE UPDATE ON warehouse.products
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON TABLE  warehouse.products IS 'Skladová karta - typ dílu. SKU = katalogové číslo dodavatele.';
COMMENT ON COLUMN warehouse.products.quantity_on_hand IS 'Denormalizované aktuální množství. Skutečné množství = SUM(stock_movements). Udržuje trigger.';

-- ============================================================================
-- 3. GOODS_RECEIPTS - příjemka (hlavička jedné PDF faktury)
-- ============================================================================
-- Jedna dodavatelská PDF faktura = jedna příjemka. Číslo faktury a číslo
-- objednávky žijí tady - klíčová data pro dohledatelnost a účetnictví.
CREATE TABLE warehouse.goods_receipts (
    id                     BIGSERIAL     PRIMARY KEY,
    supplier_id            BIGINT        NOT NULL,
    supplier_name_snapshot VARCHAR(255)  NOT NULL,

    invoice_number         VARCHAR(50)   NOT NULL,   -- číslo faktury z PDF
    order_number           VARCHAR(50),              -- číslo objednávky z PDF
    original_order_number  VARCHAR(50),              -- „číslo původní objednávky" z PDF

    issue_date             DATE,
    due_date               DATE,
    taxable_supply_date    DATE,                      -- DUZP (datum uskutečnění zdanitelného plnění)

    subtotal               NUMERIC(12,2) NOT NULL,    -- základ daně
    vat_amount             NUMERIC(12,2) NOT NULL,    -- DPH celkem
    total_amount           NUMERIC(12,2) NOT NULL,    -- částka k úhradě
    currency               CHAR(3)       NOT NULL DEFAULT 'CZK',

    status                 warehouse.receipt_status NOT NULL DEFAULT 'PENDING_REVIEW',
    reconciliation_ok      BOOLEAN       NOT NULL DEFAULT FALSE,  -- sedí součet položek na celkovou částku?
    extraction_model       VARCHAR(100),                          -- který AI model provedl extrakci
    source_filename        VARCHAR(255),
    source_pdf             BYTEA,                                 -- originál pro daňovou kontrolu

    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by             BIGINT,

    CONSTRAINT fk_receipts_supplier
        FOREIGN KEY (supplier_id) REFERENCES warehouse.suppliers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_receipts_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id) ON DELETE SET NULL,
    CONSTRAINT uq_receipt_invoice
        UNIQUE (supplier_id, invoice_number),         -- idempotence importu
    CONSTRAINT chk_receipt_totals
        CHECK (subtotal >= 0 AND vat_amount >= 0 AND total_amount >= 0)
);

CREATE INDEX idx_receipts_supplier   ON warehouse.goods_receipts (supplier_id);
CREATE INDEX idx_receipts_issue_date ON warehouse.goods_receipts (issue_date);
CREATE INDEX idx_receipts_status     ON warehouse.goods_receipts (status);
CREATE INDEX idx_receipts_invoice_no ON warehouse.goods_receipts (invoice_number);

CREATE TRIGGER trg_receipts_updated_at
    BEFORE UPDATE ON warehouse.goods_receipts
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON TABLE  warehouse.goods_receipts IS 'Příjemka = hlavička jedné dodavatelské PDF faktury. Nese číslo faktury a číslo objednávky.';
COMMENT ON COLUMN warehouse.goods_receipts.supplier_name_snapshot IS 'Název dodavatele zmrazený v okamžiku fakturace (faktura je neměnný doklad).';
COMMENT ON COLUMN warehouse.goods_receipts.reconciliation_ok IS 'Sedí součet položek na celkovou částku? FALSE = nutná ruční kontrola.';
COMMENT ON COLUMN warehouse.goods_receipts.source_pdf IS 'Originální PDF pro daňovou archivaci.';

-- ============================================================================
-- 4. GOODS_RECEIPT_ITEMS - řádky příjemky = šarže
-- ============================================================================
-- Každý řádek faktury je samostatná šarže konkrétního produktu s vlastní
-- nákupní cenou a vlastním zbývajícím množstvím. Tady vzniká dohledatelnost:
-- šarže -> příjemka -> číslo faktury a objednávky.
CREATE TABLE warehouse.goods_receipt_items (
    id                   BIGSERIAL     PRIMARY KEY,
    goods_receipt_id     BIGINT        NOT NULL,
    product_id           BIGINT        NOT NULL,
    position             SMALLINT      NOT NULL DEFAULT 0,    -- pozice z PDF

    name_snapshot        VARCHAR(500)  NOT NULL,             -- název doslovně z faktury
    quantity_received    NUMERIC(12,3) NOT NULL,             -- kolik přišlo (neměnné)
    quantity_remaining   NUMERIC(12,3) NOT NULL,             -- kolik ze šarže ještě zbývá
    unit_price_excl_vat  NUMERIC(12,2) NOT NULL,             -- nákupní cena této šarže
    vat_rate             SMALLINT      NOT NULL,
    total_incl_vat       NUMERIC(12,2) NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_items_receipt
        FOREIGN KEY (goods_receipt_id) REFERENCES warehouse.goods_receipts(id) ON DELETE CASCADE,
    CONSTRAINT fk_items_product
        FOREIGN KEY (product_id) REFERENCES warehouse.products(id) ON DELETE RESTRICT,
    CONSTRAINT chk_items_received  CHECK (quantity_received > 0),
    CONSTRAINT chk_items_remaining CHECK (quantity_remaining >= 0),
    CONSTRAINT chk_items_vat       CHECK (vat_rate >= 0 AND vat_rate <= 100)
);

CREATE INDEX idx_items_receipt ON warehouse.goods_receipt_items (goods_receipt_id);
CREATE INDEX idx_items_product ON warehouse.goods_receipt_items (product_id);

COMMENT ON TABLE  warehouse.goods_receipt_items IS 'Řádky příjemky = šarže. Nositel dohledatelnosti a historie nákupních cen.';
COMMENT ON COLUMN warehouse.goods_receipt_items.quantity_remaining IS 'Zbývající množství šarže. Při RECEIPT inicializováno = quantity_received, dále upravováno triggerem pohybů.';

-- ============================================================================
-- 5. STOCK_MOVEMENTS - deník pohybů (zdroj pravdy o množství)
-- ============================================================================
-- Append-only deník skladu. Každý příjem, výdej, vratka i korekce je
-- samostatný řádek. Skutečné skladové množství = součet pohybů.
CREATE TABLE warehouse.stock_movements (
    id              BIGSERIAL     PRIMARY KEY,
    product_id      BIGINT        NOT NULL,
    batch_id        BIGINT,                              -- která šarže (NULL u obecných korekcí)
    movement_type   warehouse.movement_type NOT NULL,
    quantity        NUMERIC(12,3) NOT NULL,              -- + příjem, - výdej

    order_id        BIGINT,                              -- výdej na servisní zakázku
    return_reason   warehouse.return_reason,             -- jen pro RETURN
    credit_note_number VARCHAR(50),                      -- číslo dobropisu (dorazí později)
    note            VARCHAR(500),

    moved_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_mov_product
        FOREIGN KEY (product_id) REFERENCES warehouse.products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mov_batch
        FOREIGN KEY (batch_id) REFERENCES warehouse.goods_receipt_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mov_order
        FOREIGN KEY (order_id) REFERENCES "order".orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mov_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id) ON DELETE SET NULL,

    -- znaménko množství odpovídá typu pohybu
    CONSTRAINT chk_movement_sign CHECK (
        (movement_type = 'RECEIPT'    AND quantity > 0) OR
        (movement_type = 'ADJUSTMENT' AND quantity <> 0) OR
        (movement_type IN ('ISSUE', 'RETURN', 'WRITE_OFF') AND quantity < 0)
    ),
    -- důvod vratky právě tehdy (a jen tehdy), když jde o vratku
    CONSTRAINT chk_return_reason CHECK (
        (movement_type =  'RETURN' AND return_reason IS NOT NULL) OR
        (movement_type <> 'RETURN' AND return_reason IS NULL)
    )
);

CREATE INDEX idx_mov_product ON warehouse.stock_movements (product_id);
CREATE INDEX idx_mov_batch   ON warehouse.stock_movements (batch_id);
CREATE INDEX idx_mov_order   ON warehouse.stock_movements (order_id);
CREATE INDEX idx_mov_type    ON warehouse.stock_movements (movement_type);

COMMENT ON TABLE  warehouse.stock_movements IS 'Append-only deník pohybů. Zdroj pravdy o skladovém množství. Nikdy se needituje.';
COMMENT ON COLUMN warehouse.stock_movements.quantity IS 'Množství se znaménkem: + příjem, - výdej. Vázáno na movement_type přes CHECK.';

-- ----------------------------------------------------------------------------
-- Trigger: promítnutí pohybu do denormalizovaného množství
-- ----------------------------------------------------------------------------
-- Po vložení pohybu automaticky upraví quantity_on_hand produktu
-- a quantity_remaining šarže. Množství zůstává konzistentní s deníkem
-- pohybů bez ohledu na to, kterou aplikační cestou byl pohyb zapsán.
--
-- RECEIPT šarži NEUPRAVUJE - šarže vzniká rovnou s
-- remaining = quantity_received (díl dorazil „plný"). Ostatní pohyby
-- remaining mění.
CREATE OR REPLACE FUNCTION warehouse.fn_apply_stock_movement()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE warehouse.products
       SET quantity_on_hand = quantity_on_hand + NEW.quantity
     WHERE id = NEW.product_id;

    IF NEW.batch_id IS NOT NULL AND NEW.movement_type <> 'RECEIPT' THEN
        UPDATE warehouse.goods_receipt_items
           SET quantity_remaining = quantity_remaining + NEW.quantity
         WHERE id = NEW.batch_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_stock_movement
    AFTER INSERT ON warehouse.stock_movements
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_apply_stock_movement();

-- ============================================================================
-- VIEW pro časté dotazy
-- ============================================================================

-- Aktuální skladové množství podle produktu.
CREATE VIEW warehouse.v_stock_on_hand AS
SELECT p.id          AS product_id,
       p.sku,
       p.name,
       p.unit,
       p.quantity_on_hand
FROM warehouse.products p
WHERE p.is_active;

-- Dohledatelnost: každá šarže se zbývajícím množstvím a zdrojovou fakturou + objednávkou.
CREATE VIEW warehouse.v_batch_provenance AS
SELECT gri.id                  AS batch_id,
       p.sku,
       gri.name_snapshot,
       gri.quantity_remaining,
       gri.unit_price_excl_vat,
       gr.invoice_number,
       gr.order_number,
       gr.issue_date,
       s.name                  AS supplier_name
FROM warehouse.goods_receipt_items gri
JOIN warehouse.products       p  ON p.id  = gri.product_id
JOIN warehouse.goods_receipts gr ON gr.id = gri.goods_receipt_id
JOIN warehouse.suppliers      s  ON s.id  = gr.supplier_id;
