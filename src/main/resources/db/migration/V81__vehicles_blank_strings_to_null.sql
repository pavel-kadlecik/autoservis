-- =============================================================================
-- V81: Datová oprava — prázdné řetězce ve vehicle.vehicles převést na NULL
-- =============================================================================
-- Stejná třída chyby jako V80 (customer.customers): frontend posílá nevyplněná
-- volitelná textová pole jako '' a backend je nenormalizoval. U vozidel navíc
-- CHECK chk_vehicles_engine_code_not_blank (V19) prázdný řetězec odmítá, takže
-- založení/editace vozidla s nevyplněným kódem motoru padaly na 422.
--
-- Kód nově normalizuje blank → NULL ve VehicleConverter; tato migrace srovnává
-- data zapsaná před opravou. engine_code v ní chybí záměrně — '' se do něj
-- kvůli CHECKu nikdy dostat nemohl (INSERT/UPDATE s '' spadl celý).

UPDATE vehicle.vehicles SET license_plate = NULL WHERE license_plate = '';
UPDATE vehicle.vehicles SET color         = NULL WHERE color         = '';
UPDATE vehicle.vehicles SET internal_note = NULL WHERE internal_note = '';
