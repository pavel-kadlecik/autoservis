-- =============================================================================
-- V70__order_mileage_at_intake.sql
-- Schéma: order
--
-- Stav tachometru při příjmu vozu do servisu — údaj zakázkového listu.
-- Audit 2026-07-30: KN-28 (chybí přijímací protokol) + 07/P-14 (km při příjmu se nikde
-- neevidují, což je nevratná datová ztráta).
--
-- Proč sloupec na zakázce, když existuje vehicle.mileage_history:
--   Zakázkový list je doklad, který zákazník podepisuje. Musí být reprodukovatelný i po
--   letech a po dalších odečtech tachometru — proto snímek na zakázce, ne odkaz na
--   „aktuální" stav vozu (týž princip jako snapshoty na faktuře, V50).
--   Odečet do historie vozidla se zapisuje ZÁROVEŇ (OrderServiceImpl.create, zdroj SERVICE),
--   aby se km z příjmu propsaly i do vehicle.current_mileage_km. Dvě čísla o téže věci jsou
--   tu vědomě: jedno patří dokladu, druhé odometru vozu.
--
-- Nullable: starší zakázky ho nemají a vyplnění je i do budoucna nepovinné (vůz může přijet
-- odtažený s nefunkčním tachometrem). Bez backfillu — hodnotu nelze zpětně poznat.
--
-- CHECK zrcadlí vehicle.mileage_history (V20:29), ať se stejný údaj validuje stejně.
-- =============================================================================

ALTER TABLE "order".orders
    ADD COLUMN mileage_km_at_intake INTEGER,
    ADD CONSTRAINT chk_orders_mileage_at_intake
        CHECK (mileage_km_at_intake IS NULL
               OR (mileage_km_at_intake >= 0 AND mileage_km_at_intake <= 9999999));

COMMENT ON COLUMN "order".orders.mileage_km_at_intake IS
    'Stav tachometru [km] při příjmu vozu — snímek pro zakázkový list (KN-28). '
    'NULL = nezadáno. Odometr vozidla vede vehicle.mileage_history, tohle je údaj dokladu.';
