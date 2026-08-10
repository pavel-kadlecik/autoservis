-- =============================================================================
-- V77__reduce_appointment_status.sql
-- Schéma: schedule
--
-- Redukce ENUM schedule.appointment_status z 5 hodnot na 4 — ruší se CONFIRMED.
--   PLANNED, CONVERTED, NO_SHOW, CANCELLED
-- Mapování: CONFIRMED -> PLANNED
--
-- Proč (rozhodnutí uživatele 2026-08-04):
--   Objednávka vzniká po telefonu se zákazníkem na lince, takže je potvrzená už v okamžiku
--   založení. „Naplánováno" a „Potvrzeno" tím znamenaly totéž a obsluha musela přemýšlet,
--   které vybrat. Nepoužívaný stav je horší než žádný — čtenář kalendáře si o něm myslí,
--   že něco znamená.
--
--   Servis zákazníky před termínem neobvolává a termíny nezapisuje „tužkou" před domluvou;
--   v obou těch případech by CONFIRMED smysl mělo. Až přibudou SMS připomínky, kde by
--   znamenalo „zákazník odpověděl", se hodnota přidá zpátky — přidat hodnotu do ENUMu je
--   levnější než ji odebírat (vzor V17).
--
-- Životní cyklus po změně:
--   PLANNED → CONVERTED (auto přijelo, vznikla zakázka)
--           → NO_SHOW   (nepřijelo a nikdo se neozval)
--           → CANCELLED (zákazník zavolal, že nepřijede)
--
-- Postup kopíruje V24 (redukce order_item_type): PostgreSQL neumí z ENUMu hodnotu odebrat,
-- takže se staví nový typ a sloupec se přes TEXT přelije. Navíc oproti V24 je tu DEFAULT
-- a CHECK nad tímtéž sloupcem — obojí musí dočasně pryč, jinak přetypování neprojde.
-- =============================================================================

-- 1) Nový typ bez CONFIRMED (dočasný název, ať nekoliduje se stávajícím)
CREATE TYPE schedule.appointment_status_new AS ENUM (
    'PLANNED', 'CONVERTED', 'NO_SHOW', 'CANCELLED'
);

-- 2) CHECK nad status a DEFAULT dočasně pryč — oba jsou vázané na starý typ
ALTER TABLE schedule.appointments
    DROP CONSTRAINT chk_appointments_converted_order;

ALTER TABLE schedule.appointments
    ALTER COLUMN status DROP DEFAULT;

-- 3) Sloupec dočasně na TEXT — bez pravidel ENUMu do něj lze zapsat i přemapovanou hodnotu
ALTER TABLE schedule.appointments
    ALTER COLUMN status TYPE TEXT USING status::text;

-- 4) Přemapování: potvrzené objednávky jsou prostě naplánované
UPDATE schedule.appointments
SET status = 'PLANNED'
WHERE status = 'CONFIRMED';

-- 5) Přepnutí na nový ENUM
ALTER TABLE schedule.appointments
    ALTER COLUMN status TYPE schedule.appointment_status_new
        USING status::schedule.appointment_status_new;

-- 6) Starý typ zahodit a nový přejmenovat na původní název
DROP TYPE schedule.appointment_status;
ALTER TYPE schedule.appointment_status_new RENAME TO appointment_status;

-- 7) DEFAULT a CHECK zpátky, už nad novým typem
ALTER TABLE schedule.appointments
    ALTER COLUMN status SET DEFAULT 'PLANNED';

ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_converted_order
        CHECK (status <> 'CONVERTED' OR order_id IS NOT NULL);
