-- ============================================================================
-- V87 — pohyb skladu přežije smazání zakázky
-- ============================================================================
-- Zakázku, ze které odešel materiál, dosud nešlo smazat vůbec: `fk_mov_order`
-- má ON DELETE RESTRICT, takže cizí klíč mazání zablokoval. Omylem založená
-- zakázka (překlep, špatné auto), na kterou stihl někdo vydat díl, tak zůstala
-- v evidenci navždy — i když se materiál vrátil a sklad byl zase v pořádku.
--
-- Rozhodnutí uživatele 2026-08-07: mazání má být možné a materiál se má vrátit
-- sám, stejně jako to od 2026-08-06 dělá zrušení.
--
-- PROČ SE FK ZAHAZUJE, MÍSTO ABY SE ZMĚNIL NA SET NULL:
-- `stock_movements` je append-only — trigger `trg_movements_append_only` (V52)
-- zakazuje UPDATE i DELETE. SET NULL je UPDATE, takže by ho ten trigger odmítl
-- a mazání zakázky by spadlo na výjimce. Kaskáda nepřipadá v úvahu vůbec: mazat
-- pohyby znamená přepisovat skladovou historii, proti čemuž ten trigger je.
--
-- Zůstává tedy sloupec bez cizího klíče — týž vzor a týž důvod jako u
-- `order_item_id` ve V83. Pohyb po smazání zakázky nese její ID, které už na nic
-- neukazuje; `id` se v PostgreSQLu nerecykluje, takže odkaz zůstává jednoznačný
-- a historie skladu pravdivá: materiál opravdu odešel a vrátil se, i když záznam
-- o zakázce později zmizel.
--
-- Integritu drží místo databáze aplikace: `OrderServiceImpl.delete` nejdřív vrátí
-- veškerý vydaný materiál, takže po smazané zakázce zůstává jen vyrovnaný pár
-- pohybů s nulovým dopadem na zásobu.
--
-- Dotazy nad pohyby už s chybějící zakázkou počítají (`LEFT JOIN` ve
-- `WarehouseMapper.findMovementsByProductId`), takže se řádky z výpisu nevytratí.
-- ============================================================================

ALTER TABLE warehouse.stock_movements
    DROP CONSTRAINT fk_mov_order;

COMMENT ON COLUMN warehouse.stock_movements.order_id IS
    'Zakázka, kvůli které pohyb vznikl. Bez FK (V87) — zakázku lze smazat a pohyb '
    'v append-only ledgeru zůstává. ID se nerecykluje, odkaz je proto jednoznačný '
    'i po smazání.';
