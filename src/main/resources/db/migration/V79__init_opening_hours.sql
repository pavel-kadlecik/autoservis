-- =============================================================================
-- V79__init_opening_hours.sql
-- Schéma: schedule
--
-- Otevírací doba dílny — týdenní rozvrh, na který se ohlíží plánovací kalendář.
--
-- Proč to patří ke kalendáři a ne k firemnímu profilu:
--   Otevírací doba není údaj na fakturu, je to provozní pravidlo. Kalendář podle ní kreslí
--   zavřené dny a upozorňuje, když termín padne mimo. Modul = schéma (konvence R-02).
--
-- Co otevírací doba NEomezuje:
--   Jak dlouho auto stojí v dílně. Přes noc v zavřené dílně stojí běžně a vícedenní opravy
--   (V74) na tom stojí. Rozvrh se týká jen PŘÍJEZDU a VYZVEDNUTÍ — tedy okamžiků, kdy
--   u toho musí někdo být.
--
-- Dvě tabulky, ne jedna:
--   opening_hours    — sedm řádků, samotný rozvrh
--   schedule_settings — jeden řádek, provozní přepínače kalendáře
--   Přepínač je globální, ne vlastnost dne; naskládat ho do každého ze sedmi řádků by
--   znamenalo, že může být v sedmi různých stavech.
-- =============================================================================

-- =============================================================================
-- schedule.opening_hours — týdenní rozvrh
-- =============================================================================
CREATE TABLE schedule.opening_hours (
    -- 1 = pondělí … 7 = neděle (ISO-8601). Shodné s EXTRACT(ISODOW) v SQL i s java.time.DayOfWeek,
    -- takže nikde nevzniká převodní tabulka, kde by se dalo splést pondělí s nedělí.
    day_of_week SMALLINT      NOT NULL,
    opens_at    TIME          NULL,
    closes_at   TIME          NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_opening_hours PRIMARY KEY (day_of_week),
    CONSTRAINT chk_opening_hours_day
        CHECK (day_of_week BETWEEN 1 AND 7),

    -- Obě NULL = zavřeno celý den. Jedna vyplněná a druhá ne by znamenala „otevřeno
    -- od sedmi do neznáma" — takový stav nemá význam a nesmí vzniknout.
    CONSTRAINT chk_opening_hours_pair
        CHECK ((opens_at IS NULL) = (closes_at IS NULL)),

    -- Přes půlnoc se neotevírá; noční směna v autoservisu není případ, který řešíme.
    CONSTRAINT chk_opening_hours_range
        CHECK (closes_at IS NULL OR closes_at > opens_at)
);

COMMENT ON TABLE schedule.opening_hours IS
    'Týdenní otevírací doba dílny. Sedm řádků (1 = pondělí … 7 = neděle); '
    'opens_at i closes_at NULL znamená zavřeno celý den.';

CREATE TRIGGER trg_opening_hours_updated_at
    BEFORE UPDATE ON schedule.opening_hours
    FOR EACH ROW EXECUTE FUNCTION schedule.fn_set_updated_at();

-- Výchozí rozvrh: po–pá 7:00–17:00, víkend zavřeno. Prázdná tabulka by znamenala,
-- že rozvrh chybí, a kalendář by musel řešit stav „některé dny nevím" — sedm řádků
-- existuje vždy a mění se jen jejich obsah.
INSERT INTO schedule.opening_hours (day_of_week, opens_at, closes_at) VALUES
    (1, TIME '07:00', TIME '17:00'),
    (2, TIME '07:00', TIME '17:00'),
    (3, TIME '07:00', TIME '17:00'),
    (4, TIME '07:00', TIME '17:00'),
    (5, TIME '07:00', TIME '17:00'),
    (6, NULL, NULL),
    (7, NULL, NULL);

-- =============================================================================
-- schedule.schedule_settings — provozní přepínače kalendáře (jeden řádek)
-- =============================================================================
CREATE TABLE schedule.schedule_settings (
    -- Singleton vynucený CHECKem — týž vzor jako billing.company_profile.
    id                     SMALLINT    NOT NULL,
    opening_hours_enabled  BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_schedule_settings PRIMARY KEY (id),
    CONSTRAINT chk_schedule_settings_singleton CHECK (id = 1)
);

COMMENT ON COLUMN schedule.schedule_settings.opening_hours_enabled IS
    'Zapíná ohled na otevírací dobu. Dnes znamená „upozorňuj na termín mimo dobu" '
    '(uložit lze i tak — rozhodnutí uživatele 2026-08-04); zavřené dny se ztlumí v kalendáři.';

CREATE TRIGGER trg_schedule_settings_updated_at
    BEFORE UPDATE ON schedule.schedule_settings
    FOR EACH ROW EXECUTE FUNCTION schedule.fn_set_updated_at();

-- Výchozí VYPNUTO: rozvrh výše je jen odhad. Servis si ho nejdřív upraví podle sebe
-- a hlídání zapne, až bude sedět — jinak by migrace začala upozorňovat na objednávky,
-- které v databázi už jsou a nikdo je nekontroloval.
INSERT INTO schedule.schedule_settings (id, opening_hours_enabled) VALUES (1, FALSE);
