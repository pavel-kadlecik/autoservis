-- =============================================================================
-- V83__add_order_item_id_to_stock_movements.sql
-- Schéma: warehouse
--
-- Vazba skladového pohybu na konkrétní položku zakázky — základ rezervačního modelu
-- (rozprava 2026-08-05, Etapa 1).
--
-- PROČ:
--   Nově platí, že přidání dílu na zakázku je jen REZERVACE (drží šarži, snižuje dostupné
--   množství, nemění fyzický stav) a teprve výdej je skladový pohyb. Rezervace se neukládá,
--   odvozuje se: „položka s vazbou na šarži, na neuzavřené zakázce, ke které zatím neexistuje
--   výdejový pohyb". Ledger zůstává jediným zdrojem pravdy — druhý příznak vedle něj by se
--   s ním mohl rozejít.
--   K tomu je potřeba vědět, ke KTERÉ položce pohyb patří. Dosud pohyb nesl jen order_id,
--   takže u zakázky s více řádky z různých šarží nešlo rozlišit vydané od nevydaných.
--   Vazba zároveň umožní přesně dohledat, který pohyb kterou položku vydal a vrátil —
--   to potřebuje odškrtávací dialog při rušení dokončené zakázky i znovuotevření, které
--   výdej vrací zpět do rezervace.
--
-- NULLABLE: ruční pohyby (ADJUSTMENT, WRITE_OFF, RETURN dodavateli) a příjmy k žádné položce
--   zakázky nepatří.
--
-- PROČ BEZ CIZÍHO KLÍČE (odchylka od konvence „FK všude", rozhodnutí 2026-08-05):
--   Ledger je od V52 tvrdě append-only — trigger trg_movements_append_only zakáže UPDATE
--   i DELETE nad pohybem. Cizí klíč by musel mít ON DELETE:
--     · CASCADE   — smazal by řádky ledgeru; proti smyslu append-only,
--     · SET NULL  — vyvolal by UPDATE nad pohybem → trigger ho shodí, takže by PŘESTALO
--                   fungovat mazání položky zakázky, které dnes běžně funguje,
--     · RESTRICT  — zablokoval by mazání vydané položky, které má zůstat možné.
--   Žádná varianta tedy neprojde bez toho, aby něco rozbila. Sloupec proto nese id jako
--   ÚDAJ, ne jako hlídaný odkaz — týž princip jako snímky na faktuře (V50): ledger si
--   pamatuje, co se tenkrát stalo, i když zdrojový záznam později zmizí. Sekvence id se
--   v PostgreSQL nerecykluje, takže odkaz nemůže ukázat na cizí položku.
--
-- BACKFILL: bez něj by položky dnešních otevřených zakázek — které JIŽ VYDANÉ JSOU, jen
--   pohybem bez této vazby — vypadaly jako nevydané, tedy rezervované. Dostupné množství by
--   u nich kleslo dvakrát: jednou skutečným výdejem, podruhé fantomovou rezervací, a u dílů
--   s malou zásobou by to hned zablokovalo další zakázky.
-- =============================================================================

ALTER TABLE warehouse.stock_movements
    ADD COLUMN order_item_id BIGINT,
    -- Pohyb vázaný na položku musí nést i zakázku, ze které položka je. Bez toho by šlo
    -- uložit pohyb s položkou, ale bez zakázky, a rozpad podle zakázek by ho neviděl.
    ADD CONSTRAINT chk_movement_order_item
        CHECK (order_item_id IS NULL OR order_id IS NOT NULL);

CREATE INDEX idx_mov_order_item ON warehouse.stock_movements (order_item_id);

COMMENT ON COLUMN warehouse.stock_movements.order_item_id IS
    'Položka zakázky, které se pohyb týká. NULL u příjmů a ručních pohybů (ADJUSTMENT, '
    'WRITE_OFF, RETURN). Rozlišuje vydané položky od pouze rezervovaných — rezervace se '
    'z toho odvozuje, neukládá se. Záměrně BEZ cizího klíče: ledger je append-only (V52), '
    'takže ON DELETE by musel buď mazat/měnit pohyb, nebo zablokovat mazání položky. '
    'Id se v PostgreSQL nerecykluje, odkaz proto zůstává jednoznačný i po smazání položky.';

-- -----------------------------------------------------------------------------
-- Backfill
--
-- Trigger trg_movements_append_only (V52) zakazuje UPDATE nad pohybem, takže se na dobu
-- backfillu vypíná. Je to vědomá a jednorázová výjimka: trigger brání APLIKACI přepisovat
-- historii, tady jde o doplnění nového sloupce v rámci schémové migrace, které samo o sobě
-- žádné množství ani stav skladu nemění. Zapíná se hned zpátky ve stejné transakci.
--
-- Import z příjemky zakládá na každý řádek požadavku jednu položku a jeden pohyb ISSUE
-- se stejnou šarží i množstvím (OrderItemServiceImpl.importFromReceipt), takže párování
-- podle zakázky, šarže a množství sedí. ROW_NUMBER zajistí, že dva shodné řádky dostanou
-- dvě různé položky — jinak by jedna zůstala nespárovaná a tvářila se jako rezervovaná.
-- Pořadí podle id, aby byl výsledek reprodukovatelný.
--
-- Množství: ISSUE je záporné (chk_movement_sign), položka zakázky kladná → oi.quantity = -m.quantity.
--
-- NEJDŘÍV SE ALE VYŘADÍ „SPOTŘEBOVANÉ" VÝDEJE. Smazání položky ze zakázky dnes zakládá
-- vratku ISSUE_RETURN (OrderItemServiceImpl.delete) a samotnou položku smaže — v deníku tak
-- po ní zůstane dvojice výdej + vratka, ke které už žádná živá položka neexistuje. Kdyby
-- takový výdej do párování vstoupil, dostal by vazbu na CIZÍ položku (ověřeno: u zakázky,
-- kde se položka smazala a stejný díl se ze stejné šarže naimportoval znovu, sebral vazbu
-- starý pohyb a ten správný zůstal osiřelý). Po spárování a vyřazení těchto dvojic zbydou
-- přesně pohyby živých položek. Backfill běží jen jednou, takže chyba by v datech zůstala
-- napořád — proto ten krok navíc.
-- -----------------------------------------------------------------------------

ALTER TABLE warehouse.stock_movements DISABLE TRIGGER trg_movements_append_only;

-- 0. průchod: výdeje zrušené vratkou (patřily položkám, které už byly smazány)
-- Bez ON COMMIT DROP: kdyby migrace neběžela v jedné transakci, tabulka by zmizela dřív,
-- než ji další dva průchody použijí. Uklízí se explicitně na konci.
CREATE TEMP TABLE tmp_spent_issues AS
WITH iss AS (
    SELECT id, order_id, batch_id, quantity,
           ROW_NUMBER() OVER (PARTITION BY order_id, batch_id, quantity ORDER BY id) AS rn
      FROM warehouse.stock_movements
     WHERE movement_type = 'ISSUE'
       AND order_id IS NOT NULL
       AND batch_id IS NOT NULL
),
ret AS (
    SELECT order_id, batch_id, quantity,
           ROW_NUMBER() OVER (PARTITION BY order_id, batch_id, quantity ORDER BY id) AS rn
      FROM warehouse.stock_movements
     WHERE movement_type = 'ISSUE_RETURN'
       AND order_id IS NOT NULL
       AND batch_id IS NOT NULL
)
SELECT iss.id
  FROM iss
  JOIN ret ON ret.order_id = iss.order_id
          AND ret.batch_id = iss.batch_id
          AND ret.quantity = -iss.quantity
          AND ret.rn       = iss.rn;

-- 1. průchod: přesná shoda včetně množství
WITH issue_mov AS (
    SELECT id, order_id, batch_id, quantity,
           ROW_NUMBER() OVER (PARTITION BY order_id, batch_id, quantity ORDER BY id) AS rn
      FROM warehouse.stock_movements
     WHERE movement_type = 'ISSUE'
       AND order_id      IS NOT NULL
       AND batch_id      IS NOT NULL
       AND order_item_id IS NULL
       AND id NOT IN (SELECT id FROM tmp_spent_issues)
),
issued_item AS (
    SELECT id, order_id, goods_receipt_item_id, quantity,
           ROW_NUMBER() OVER (PARTITION BY order_id, goods_receipt_item_id, quantity ORDER BY id) AS rn
      FROM "order".order_items
     WHERE goods_receipt_item_id IS NOT NULL
)
UPDATE warehouse.stock_movements sm
   SET order_item_id = ii.id
  FROM issue_mov im
  JOIN issued_item ii
    ON ii.order_id              = im.order_id
   AND ii.goods_receipt_item_id = im.batch_id
   AND ii.quantity              = -im.quantity
   AND ii.rn                    = im.rn
 WHERE sm.id = im.id;

-- 2. průchod: zbytek už jen podle zakázky a šarže, bez ohledu na množství.
-- Pojistka pro případ, že se množství položky po importu změnilo (editace položky dnes
-- sklad nijak neupravuje, takže se čísla mohla rozejít). Bez ní by taková položka zůstala
-- nespárovaná a napořád by se počítala jako rezervovaná.
-- Bere jen položky, které si v 1. průchodu nikdo nevzal.
WITH issue_mov AS (
    SELECT id, order_id, batch_id,
           ROW_NUMBER() OVER (PARTITION BY order_id, batch_id ORDER BY id) AS rn
      FROM warehouse.stock_movements
     WHERE movement_type = 'ISSUE'
       AND order_id      IS NOT NULL
       AND batch_id      IS NOT NULL
       AND order_item_id IS NULL
       AND id NOT IN (SELECT id FROM tmp_spent_issues)
),
issued_item AS (
    SELECT oi.id, oi.order_id, oi.goods_receipt_item_id,
           ROW_NUMBER() OVER (PARTITION BY oi.order_id, oi.goods_receipt_item_id ORDER BY oi.id) AS rn
      FROM "order".order_items oi
     WHERE oi.goods_receipt_item_id IS NOT NULL
       AND NOT EXISTS (SELECT 1
                         FROM warehouse.stock_movements m
                        WHERE m.order_item_id = oi.id)
)
UPDATE warehouse.stock_movements sm
   SET order_item_id = ii.id
  FROM issue_mov im
  JOIN issued_item ii
    ON ii.order_id              = im.order_id
   AND ii.goods_receipt_item_id = im.batch_id
   AND ii.rn                    = im.rn
 WHERE sm.id = im.id;

DROP TABLE tmp_spent_issues;

ALTER TABLE warehouse.stock_movements ENABLE TRIGGER trg_movements_append_only;

-- -----------------------------------------------------------------------------
-- Kontrola po nasazení (nespouští se, jen návod):
--
--   -- Položky, které by se po migraci tvářily jako rezervované, ačkoli vydané jsou.
--   -- Očekávaný výsledek: 0 řádků.
--   SELECT oi.id, oi.order_id, oi.name, oi.quantity
--     FROM "order".order_items oi
--     JOIN "order".orders o ON o.id = oi.order_id
--    WHERE oi.goods_receipt_item_id IS NOT NULL
--      AND o.status NOT IN ('COMPLETED', 'CANCELLED')
--      AND NOT EXISTS (SELECT 1 FROM warehouse.stock_movements m WHERE m.order_item_id = oi.id);
-- -----------------------------------------------------------------------------
