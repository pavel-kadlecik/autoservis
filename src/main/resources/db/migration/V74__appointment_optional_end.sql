-- =============================================================================
-- V74__appointment_optional_end.sql
-- Schéma: schedule
--
-- Konec objednávky je nově VOLITELNÝ.
--
-- Proč: servis má dva druhy objednávek a V72 uměla jen jeden.
--   1) Zákazník počká — ví se od kdy do kdy (výměna oleje, přezutí).
--   2) Zákazník nechá auto — ví se, kdy dorazí, ale ne kdy bude hotovo.
--      Mechanik délku opravy před diagnostikou odhadnout nedokáže.
--
-- Původní NOT NULL nutil obsluhu konec vymyslet. Do dat se tím dostávalo číslo,
-- které nikdo netvrdil, a kalendář podle něj kreslil délku, která nikde neplatila.
-- NULL znamená doslova „konec neznámý" — přesně to, co NULL v SQL znamená.
--
-- Rozhodnutí uživatele 2026-08-03: třetí varianta („přiveze to ve středu, čas
-- neřešíme") se NEZAVÁDÍ. Čas příjezdu je znám vždy, protože ho servis musí
-- zákazníkovi sdělit. Proto zůstává starts_at NOT NULL a nepřibývá příznak
-- celodenní události — dva tvary objednávky se poznají podle jediného údaje.
--
-- Blokace dílny (CLOSURE) konec mít MUSÍ: „zavřeno navždy" nedává smysl a bez
-- konce by blokace zablokovala každou budoucí objednávku.
-- =============================================================================

ALTER TABLE schedule.appointments
    ALTER COLUMN ends_at DROP NOT NULL;

-- Původní CHECK porovnával dva NOT NULL sloupce; teď musí prázdný konec propustit.
ALTER TABLE schedule.appointments
    DROP CONSTRAINT chk_appointments_time_range;

ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_time_range
        CHECK (ends_at IS NULL OR ends_at > starts_at);

ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_closure_has_end
        CHECK (entry_type <> 'CLOSURE' OR ends_at IS NOT NULL);

COMMENT ON COLUMN schedule.appointments.ends_at IS
    'Konec termínu. NULL = „zákazník nechá auto, konec neznámý" — délku opravy '
    'nelze před diagnostikou odhadnout. U blokace dílny (CLOSURE) je povinný.';
