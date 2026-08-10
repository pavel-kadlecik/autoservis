-- ============================================================================
-- V40__product_identity_supplier_products.sql
--
-- Identita produktu napříč dodavateli (přepracování skladového importu, fáze 5).
--
-- Problém: products.sku drží katalogové číslo toho dodavatele, od kterého díl
-- dorazil POPRVÉ. Stejný fyzický díl od druhého dodavatele má jiný kód
-- -> vznikla by duplicitní skladová karta.
--
-- Řešení (standardní ERP vzor - křížová reference položek dodavatele):
--   1. products dostanou číslo dílu výrobce + normalizovanou variantu
--      (identita pro párování; sku zůstává jako hlavní katalogové číslo
--      pro uživatele),
--   2. nová tabulka warehouse.supplier_products mapuje (dodavatel, kód
--      dodavatele) -> produkt. Potvrzené shody sem upsertuje review workflow,
--      takže se mapování samo učí.
--   3. pg_trgm umožňuje návrhy podle podobnosti názvu v párovací kaskádě.
--
-- Backfill: sku každého existujícího produktu se zaznamená jako křížová
-- reference dodavatele, od kterého poprvé dorazil (původ: nejstarší šarže ->
-- příjemka -> dodavatel). Produkty bez šarží (ruční karty) původ nemají
-- a křížovou referenci nedostanou - správně.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- ----------------------------------------------------------------------------
-- 1. Sloupce identity produktu
-- ----------------------------------------------------------------------------

ALTER TABLE warehouse.products
    ADD COLUMN manufacturer_part_number VARCHAR(100),
    ADD COLUMN part_number_normalized VARCHAR(100)
        GENERATED ALWAYS AS (
            NULLIF(regexp_replace(upper(manufacturer_part_number), '[ .\-]', '', 'g'), '')
        ) STORED;

CREATE INDEX idx_products_part_number_norm
    ON warehouse.products (part_number_normalized);

CREATE INDEX idx_products_name_trgm
    ON warehouse.products USING gin (name public.gin_trgm_ops);

COMMENT ON COLUMN warehouse.products.manufacturer_part_number IS
    'Číslo dílu tak, jak ho tiskne výrobce (např. "871.180" u Elringu). Spolu s výrobcem tvoří identitu pro párování.';
COMMENT ON COLUMN warehouse.products.part_number_normalized IS
    'Generovaný sloupec: manufacturer_part_number velkými písmeny bez mezer/teček/pomlček. Používá ho párovací kaskáda importu.';
COMMENT ON COLUMN warehouse.products.sku IS
    'Hlavní katalogové číslo pro uživatele (SKU prvního dodavatele nebo ruční). Od V40 už NENÍ identitou pro párování - viz supplier_products.';

-- ----------------------------------------------------------------------------
-- 2. Tabulka křížových referencí dodavatelů
-- ----------------------------------------------------------------------------

CREATE TABLE warehouse.supplier_products (
    id            BIGSERIAL    PRIMARY KEY,
    supplier_id   BIGINT       NOT NULL,
    supplier_sku  VARCHAR(100) NOT NULL,
    product_id    BIGINT       NOT NULL,
    name_snapshot VARCHAR(500),
    last_unit_price_excl_vat NUMERIC(12,2),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_supplier_products_supplier
        FOREIGN KEY (supplier_id) REFERENCES warehouse.suppliers(id) ON DELETE CASCADE,
    CONSTRAINT fk_supplier_products_product
        FOREIGN KEY (product_id) REFERENCES warehouse.products(id) ON DELETE CASCADE,
    CONSTRAINT uq_supplier_products UNIQUE (supplier_id, supplier_sku)
);

CREATE INDEX idx_supplier_products_product ON warehouse.supplier_products (product_id);

CREATE TRIGGER trg_supplier_products_updated_at
    BEFORE UPDATE ON warehouse.supplier_products
    FOR EACH ROW EXECUTE FUNCTION warehouse.fn_set_updated_at();

COMMENT ON TABLE warehouse.supplier_products IS
    'Křížová reference položek dodavatele: (dodavatel, katalogové číslo dodavatele) -> skladová karta. Samoučící - potvrzené shody upsertuje review workflow.';

-- ----------------------------------------------------------------------------
-- 3. Backfill z původu šarží
-- ----------------------------------------------------------------------------

INSERT INTO warehouse.supplier_products
    (supplier_id, supplier_sku, product_id, name_snapshot, last_unit_price_excl_vat)
SELECT DISTINCT ON (p.id)
       gr.supplier_id, p.sku, p.id, p.name, gri.unit_price_excl_vat
FROM warehouse.products p
JOIN warehouse.goods_receipt_items gri ON gri.product_id = p.id
JOIN warehouse.goods_receipts gr       ON gr.id = gri.goods_receipt_id
WHERE p.sku IS NOT NULL
  AND gr.supplier_id IS NOT NULL
ORDER BY p.id, gri.created_at ASC, gri.id ASC
ON CONFLICT (supplier_id, supplier_sku) DO NOTHING;
