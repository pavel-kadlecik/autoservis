-- =============================================================================
-- V27__add_goods_receipt_item_id_to_order_item.sql
-- Schéma: order
-- Přidává cizí klíč goods_receipt_item_id do order_items.
-- =============================================================================
ALTER TABLE "order".order_items
ADD COLUMN goods_receipt_item_id BIGINT;

ALTER TABLE "order".order_items
ADD CONSTRAINT fk_order_items_goods_receipt_item

FOREIGN KEY (goods_receipt_item_id)
REFERENCES warehouse.goods_receipt_items(id) ON DELETE RESTRICT;

CREATE INDEX idx_order_items_goods_receipt_item
    ON "order".order_items (goods_receipt_item_id);
