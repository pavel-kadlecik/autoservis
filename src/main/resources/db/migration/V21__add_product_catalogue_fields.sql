-- ============================================================================
-- V21__add_product_catalogue_fields.sql
--
-- Modul WAREHOUSE - rozšiřuje skladovou kartu (warehouse.products) o katalogová
-- a cenová pole zobrazovaná ve skladovém přehledu:
--   - manufacturer / variant / note : identifikace. „Olejový filtr" je podle
--     použití mnoho různých dílů, takže variant (např. „2.0 TDI 2013-2016")
--     je to, co dělá skladovou kartu jednoznačnou.
--   - sale_price                    : cena, za kterou se díl prodává. Nákupní
--     cena zůstává ODVOZENÁ z goods_receipt_items (šarží).
--   - min_stock_level               : VOLITELNÝ práh pro doobjednání. NULL =
--     položka se nehlídá; při vyplnění se položka počítá jako docházející,
--     jakmile quantity_on_hand < min_stock_level. Je to opt-in pro
--     signál/filtr „nízká dostupnost" - žádný samostatný příznak neexistuje.
-- ============================================================================

ALTER TABLE warehouse.products
    ADD COLUMN manufacturer    VARCHAR(255),
    ADD COLUMN variant         VARCHAR(255),
    ADD COLUMN note            VARCHAR(500),
    ADD COLUMN sale_price      NUMERIC(12,2),
    ADD COLUMN min_stock_level NUMERIC(12,3);

ALTER TABLE warehouse.products
    ADD CONSTRAINT chk_products_sale_price CHECK (sale_price IS NULL OR sale_price >= 0),
    ADD CONSTRAINT chk_products_min_stock  CHECK (min_stock_level IS NULL OR min_stock_level >= 0);

CREATE INDEX idx_products_manufacturer ON warehouse.products (manufacturer);

COMMENT ON COLUMN warehouse.products.manufacturer    IS 'Výrobce / značka dílu (např. Bosch). Volný text.';
COMMENT ON COLUMN warehouse.products.variant         IS 'Varianta / použití (např. „2.0 TDI 2013-2016"). Rozlišuje díly se stejným názvem.';
COMMENT ON COLUMN warehouse.products.note            IS 'Poznámka volným textem (tipy k objednávání, alternativy).';
COMMENT ON COLUMN warehouse.products.sale_price      IS 'Prodejní cena bez DPH. Nákupní cena zůstává odvozená z goods_receipt_items.';
COMMENT ON COLUMN warehouse.products.min_stock_level IS 'Volitelný práh pro doobjednání. NULL = nehlídá se; při vyplnění je položka docházející, jakmile quantity_on_hand < min_stock_level.';
