-- =============================================================================
-- V24__reduce_order_item_type_enum.sql
-- Schéma: order
-- Redukce ENUM "order".order_item_type ze 6 hodnot na 3:
--   LABOR, MATERIAL, OTHER_SERVICES
-- Mapování starých hodnot:
--   DIAGNOSTIC            -> LABOR
--   TOWING, RENTAL, OTHER -> OTHER_SERVICES
-- Závisí na V12 (order_items).
-- =============================================================================

-- 1) Nový typ se třemi hodnotami (dočasný název, ať nekoliduje se stávajícím)
CREATE TYPE "order".order_item_type_new AS ENUM ('LABOR', 'MATERIAL', 'OTHER_SERVICES');

-- 2) Sloupec dočasně na TEXT — bez pravidel enumu do něj půjde zapsat i hodnotu,
--    která ve starém enumu neexistuje (OTHER_SERVICES)
ALTER TABLE "order".order_items
    ALTER COLUMN item_type TYPE TEXT USING item_type::text;

-- 3) Přemapování starých hodnot na nové tři
UPDATE "order".order_items SET item_type = 'LABOR'
WHERE item_type = 'DIAGNOSTIC';

UPDATE "order".order_items SET item_type = 'OTHER_SERVICES'
WHERE item_type IN ('TOWING', 'RENTAL', 'OTHER');

-- 4) Sloupec přepneme na nový ENUM (USING zajistí konverzi text -> enum)
ALTER TABLE "order".order_items
    ALTER COLUMN item_type TYPE "order".order_item_type_new
        USING item_type::"order".order_item_type_new;

-- 5) Starý typ zahodíme (už na něm nic nezávisí) a nový přejmenujeme na původní název
DROP TYPE "order".order_item_type;
ALTER TYPE "order".order_item_type_new RENAME TO order_item_type;