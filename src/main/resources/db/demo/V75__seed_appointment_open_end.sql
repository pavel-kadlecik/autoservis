-- =============================================================================
-- V75__seed_appointment_open_end.sql
-- Umístění: db/demo — POUZE dev/local a testy, nikdy produkce.
--
-- Ukázka druhého tvaru objednávky zavedeného ve V74: zákazník nechá auto
-- a konec se neví. Bez tohoto řádku by demo obsahovalo jen objednávky s pevným
-- oknem a nebylo by na čem vidět, jak se otevřený konec kreslí a validuje.
--
-- Proč novou migrací a ne úpravou V73: hotová migrace se nikdy nemění (R-09).
-- V73 je aplikovaná, změna jejího obsahu by rozešla Flyway checksum a backend
-- by přestal startovat.
--
-- Cílí se podle title, ne podle id — seed nesmí záviset na tom, jaká čísla
-- sekvence zrovna přidělila.
-- =============================================================================

UPDATE schedule.appointments
SET ends_at = NULL,
    note    = 'Vyměnit žárovky. Zákazník nechá auto, ozveme se.'
WHERE entry_type = 'BOOKING'
  AND title = 'Příprava na STK';
