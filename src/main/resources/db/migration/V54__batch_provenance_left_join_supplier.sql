-- =============================================================================
-- V54__batch_provenance_left_join_supplier.sql
-- Schéma: warehouse
--
-- Šarže z inventurního přebytku (pseudo-příjemka STOCK_TAKE, supplier_id NULL od
-- V44) mizely z view `v_batch_provenance` kvůli INNER JOIN na dodavatele — přitom
-- dohledatelnost šarže je přesně účel toho view (audit K-14/N-18). LEFT JOIN +
-- COALESCE na zmražené jméno dodavatele (u inventury „Inventura").
--
-- Stejná oprava v `WarehouseMapper.findBatchesByProductId` (mapper) řeší, že
-- přebytková šarže nebyla vidět na kartě dílu a nešla ručně korigovat.
-- =============================================================================

SET search_path TO warehouse;

DROP VIEW warehouse.v_batch_provenance;

CREATE VIEW warehouse.v_batch_provenance AS
SELECT gri.id                  AS batch_id,
       p.sku,
       gri.name_snapshot,
       gri.quantity_remaining,
       gri.unit_price_excl_vat,
       gr.invoice_number,
       gr.order_number,
       gr.issue_date,
       COALESCE(s.name, gr.supplier_name_snapshot) AS supplier_name
FROM warehouse.goods_receipt_items gri
JOIN warehouse.products       p  ON p.id  = gri.product_id
JOIN warehouse.goods_receipts gr ON gr.id = gri.goods_receipt_id
LEFT JOIN warehouse.suppliers s  ON s.id  = gr.supplier_id;
