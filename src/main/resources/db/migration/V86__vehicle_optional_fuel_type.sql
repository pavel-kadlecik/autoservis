-- =============================================================================
-- V86__vehicle_optional_fuel_type.sql
-- Schéma: vehicle
--
-- Vozidlo už nemusí mít vyplněný druh paliva.
--
-- Proč (rozhodnutí uživatele 2026-08-07):
--   Do evidence patří i technika bez vlastního pohonu — typicky přívěsné vozíky.
--   Ty se do servisu vozí na kontrolu brzd, náprav a osvětlení stejně jako auta,
--   ale žádné palivo nemají. Povinný fuel_type nutil obsluhu vybrat hodnotu, která
--   je nepravdivá: 'OTHER' říká „jiné palivo", ne „žádné". Zápis nepravdivého údaje
--   je horší než prázdné pole — v přehledu vozidel pak nejde poznat, které auto má
--   exotický pohon a které je vozík.
--
--   Přidat do ENUMu hodnotu 'NONE' by problém neřešilo, jen přesunulo: prázdná
--   informace („nevíme, čím jezdí") a informace o neexistenci pohonu („nemá motor")
--   nejsou totéž a NULL už první z nich vyjadřuje. Dvě prázdné hodnoty vedle sebe
--   by musel rozlišovat každý dotaz i každý report.
--
--   transmission je volitelná od začátku (V5) ze stejného důvodu — palivo bylo
--   povinné jen nedopatřením, tahle migrace obě pole srovnává.
--
-- Data: nic se nemaže ani nemění. Migrace jen ruší NOT NULL, takže je bezpečná
-- i na běžící produkci — všechna existující vozidla mají palivo vyplněné a
-- zůstávají platná. Zpětně nedohledáváme, které z nich je vozík.
-- =============================================================================

ALTER TABLE vehicle.vehicles
    ALTER COLUMN fuel_type DROP NOT NULL;

COMMENT ON COLUMN vehicle.vehicles.fuel_type IS
    'Druh paliva / pohonu. Volitelný (V86) — přívěsný vozík nemá motor, takže nemá '
    'ani palivo. NULL znamená „nevyplněno", hodnota OTHER naopak „jiné než uvedené".';
