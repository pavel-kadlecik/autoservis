-- =============================================================================
-- V42__v_stock_valuation.sql
-- Schéma: warehouse
-- Ocenění zásob: hodnota skladu per produkt, počítaná ze zbytků šarží
-- a jejich skutečných nákupních cen (skutečné pořizovací ceny — rozhodnutí R-A,
-- docs/analyza-sklad-2026-07.md). Počítá se z dat, neukládá se.
-- Závisí na V18 (products, goods_receipt_items).
-- =============================================================================

-- Zaokrouhlení PO ŠARŽI (ROUND(qty * price, 2)) — stejná filozofie jako cenové
-- views V25/V32/V37: haléřová konzistence mezi řádky a součty.
--
-- Šarže se nefiltrují podle stavu příjemky: existující šarže vždy vznikla
-- potvrzením (CONFIRMED) a stav skladu ji už započítal — filtrovat by znamenalo
-- rozejít hodnotu s quantity_on_hand.
--
-- Produkty bez šarží (ručně založené karty) tu zůstávají s hodnotou 0 —
-- LEFT JOIN, ať přehled skladu nezamlčí kartu jen proto, že nemá zásobu.
CREATE VIEW warehouse.v_stock_valuation AS
SELECT
    p.id                                                        AS product_id,
    p.sku,
    p.name,
    p.unit,
    p.quantity_on_hand,
    COALESCE(SUM(ROUND(gri.quantity_remaining * gri.unit_price_excl_vat, 2)), 0) AS stock_value
FROM warehouse.products p
         LEFT JOIN warehouse.goods_receipt_items gri
                   ON gri.product_id = p.id
                       AND gri.quantity_remaining > 0
WHERE p.is_active = TRUE
GROUP BY p.id, p.sku, p.name, p.unit, p.quantity_on_hand;

COMMENT ON VIEW warehouse.v_stock_valuation IS
    'Hodnota zásob per aktivní produkt: SUM(zbytek šarže * nákupní cena bez DPH), zaokrouhleno po šarži. Celkový součet si sčítá aplikace.';
