-- =============================================================================
-- V52__stock_ledger_integrity.sql
-- Schéma: warehouse
--
-- Uzavírá skladový ledger na úrovni DB (audit K-13). Doteď byl append-only jen
-- konvencí a vazba pohyb↔šarže↔produkt jen aplikační:
--   N-3: `UPDATE`/`DELETE` na stock_movements prošel bez zásahu do denormalizace
--        (quantity_on_hand / quantity_remaining) → tichý rozjezd stavu skladu.
--   N-4: pohyb mohl ukazovat na šarži CIZÍHO produktu (FK jen na batch_id) →
--        trigger by odečetl množství jednoho produktu a zůstatek šarže druhého.
--
-- Řešení: (1) BEFORE UPDATE/DELETE trigger zakáže změnu/smazání pohybu (opravy se
-- dělají kompenzačním pohybem, nikdy editací); (2) složený FK vynutí, že batch_id
-- patří témuž produktu jako pohyb (batch_id smí být NULL u obecných korekcí —
-- MATCH SIMPLE FK se pak neuplatní, což je záměr).
-- =============================================================================

SET search_path TO warehouse;

-- (1) Append-only ledger
CREATE OR REPLACE FUNCTION warehouse.fn_forbid_movement_change()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'stock_movements je append-only — UPDATE/DELETE není dovolen (pohyb id=%). Opravy dělej kompenzačním pohybem.',
        COALESCE(OLD.id, NEW.id);
END;
$$;

CREATE TRIGGER trg_movements_append_only
    BEFORE UPDATE OR DELETE ON warehouse.stock_movements
    FOR EACH ROW
    EXECUTE FUNCTION warehouse.fn_forbid_movement_change();

-- (2) Složený FK pohyb↔šarže↔produkt
ALTER TABLE warehouse.goods_receipt_items
    ADD CONSTRAINT uq_items_id_product UNIQUE (id, product_id);

ALTER TABLE warehouse.stock_movements
    DROP CONSTRAINT fk_mov_batch,
    ADD CONSTRAINT fk_mov_batch_product
        FOREIGN KEY (batch_id, product_id)
        REFERENCES warehouse.goods_receipt_items(id, product_id) ON DELETE RESTRICT;
