-- =============================================================================
-- V90__vehicle_optional_vin_add_serial_number.sql
-- Schéma: vehicle
--
-- Vozidlo už nemusí mít vyplněný VIN; nové pole „výrobní číslo" pro stroje.
--
-- Proč (rozhodnutí uživatele 2026-08-08):
--   Servis opravuje i techniku, která VIN nemá — zahradní traktory, sekačky,
--   stará vozidla s číslem karoserie. Povinný VIN takovou techniku vylučoval
--   z evidence, případně nutil obsluhu vymýšlet falešné hodnoty.
--
--   Řešení kopíruje oborový standard (Shopmonkey, Mitchell 1, Fleetio):
--   VIN je identifikátor pro externí lookupy (registr vozidel), ne podmínka
--   existence záznamu. NULL = „stroj VIN nemá"; vyplněný VIN dál podléhá
--   přísnému formátovému CHECKu i unikátnosti (UNIQUE považuje NULLy za
--   navzájem různé, formátový CHECK na NULL projde — ani jeden se nemění).
--
--   machine_serial_number je záměrně samostatný sloupec: výrobní číslo sekačky
--   má jiný formát i sémantiku než VIN a jeho nacpání do sloupce vin by rozbilo
--   formátový CHECK (vzor: Lightspeed DMS a RepairDesk vedou „VIN nebo serial
--   number" jako dvě pole).
--
-- Data: nic se nemaže ani nemění. Migrace jen ruší NOT NULL a přidává nullable
-- sloupec, takže je bezpečná i na běžící produkci — všechna existující vozidla
-- mají VIN vyplněný a zůstávají beze změny platná.
-- =============================================================================

ALTER TABLE vehicle.vehicles
    ALTER COLUMN vin DROP NOT NULL;

ALTER TABLE vehicle.vehicles
    ADD COLUMN machine_serial_number VARCHAR(50);

COMMENT ON COLUMN vehicle.vehicles.vin IS
    'Vehicle Identification Number. Volitelný (V90) — technika bez VIN (zahradní '
    'traktor, sekačka) má NULL. Vyplněný VIN musí splňovat formát (17 znaků) '
    'a unikátnost; bez VIN nefunguje načtení z registru vozidel.';

COMMENT ON COLUMN vehicle.vehicles.machine_serial_number IS
    'Výrobní/sériové číslo stroje bez VIN (V90). Volný text — formáty výrobců '
    'se liší, unikátnost se nevynucuje (různí výrobci mohou čísla sdílet).';
