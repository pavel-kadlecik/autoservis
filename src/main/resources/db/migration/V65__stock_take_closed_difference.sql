-- =============================================================================
-- V65__stock_take_closed_difference.sql
-- Schéma: warehouse
--
-- Materializace inventurních rozdílů při uzavření (audit 2026-07-30, nález KN-2).
--
-- Rozdíl se u inventury počítá proti AKTUÁLNÍMU stavu skladu, ne proti snapshotu
-- z otevření — a to je správně (R-H): výdej během počítání se nesmí přepsat.
-- Jenže `close()` nejdřív zapíše korekční pohyby a teprve pak vrátí detail, takže
-- po uzavření se `quantity_on_hand` rovná napočítanému množství a rozdíl vyjde
-- u každého řádku 0. Uzavřená inventura tak nedoloží ani jedno manko a ani jeden
-- přebytek — přestože je oba právě zaúčtovala.
--
-- Inventura je přitom doklad: §29–§30 zákona o účetnictví po ní chce průkazný
-- záznam o zjištěných rozdílech. Proto se rozdíl a stav, proti kterému byl počítán,
-- při uzavření zmrazí do vlastních sloupců a detail uzavřené inventury pak čte je,
-- ne živý stav.
--
-- Rozdíl proti `expected_quantity`: to je stav při OTEVŘENÍ soupisu a zůstává beze
-- změny — z dvojice (expected_quantity, closed_expected_quantity) je navíc vidět,
-- kolik se toho na skladě během počítání změnilo.
-- =============================================================================

SET search_path TO warehouse;

ALTER TABLE warehouse.stock_take_items
    ADD COLUMN closed_expected_quantity NUMERIC(12,3),
    ADD COLUMN closed_difference        NUMERIC(12,3);

COMMENT ON COLUMN warehouse.stock_take_items.closed_expected_quantity IS
    'Stav skladu v okamžiku uzavření inventury, proti kterému byl spočítán rozdíl (NULL = inventura ještě není uzavřená). Audit KN-2.';
COMMENT ON COLUMN warehouse.stock_take_items.closed_difference IS
    'Zjištěný rozdíl (napočítáno − stav) zmrazený při uzavření; záporný = manko, kladný = přebytek. NULL = neuzavřeno, nebo řádek nebyl počítán. Audit KN-2.';

-- Backfill se ZÁMĚRNĚ nedělá. U inventur uzavřených před touto migrací už rozdíly
-- v datech nejsou: manka jsou v ledgeru jen jako ADJUSTMENT pohyby s textovou
-- poznámkou „Inventura {číslo} — manko" (bez vazby na stock_take_id) a rekonstrukce
-- parsováním poznámek by vyrobila čísla, za která nikdo neručí. Historické inventury
-- proto zůstávají s NULL a detail u nich spadne zpět na dnešní (nulový) výpočet —
-- stejné chování jako dosud, žádná regrese. Nové uzávěrky už rozdíly nesou.
