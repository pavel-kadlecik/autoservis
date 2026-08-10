-- =============================================================================
-- V78__appointment_vehicle_required.sql
-- Schéma: schedule
--
-- Objednávka typu BOOKING musí mít vozidlo. Dosud bylo volitelné (od V72).
--
-- Proč (rozhodnutí uživatele 2026-08-04):
--   Servis pracuje na autech, ne na lidech. Objednávka bez vozidla neřekne, co přijede —
--   a SPZ je pro dílnu hlavní identifikátor, takže i karta v kalendáři zůstala poloprázdná.
--
--   Původní zdůvodnění („auto ještě nemusí být v systému") neobstálo: zákazník v systému být
--   MUSÍ (cizí klíč), a když se zakládá, vozidlo se přidá stejným dechem.
--
--   Navíc vozidlo nese zákazníka (vehicle.vehicles.customer_id, hlídá to i validace
--   VEHICLE_NOT_OWNED_BY_CUSTOMER v service). Vyžadovat vozidlo tedy dá víc informace než
--   vyžadovat zákazníka — obráceně to neplatí.
--
--   A hlavně: zakázka má vehicle_id NOT NULL. Objednávka bez auta práci neušetřila,
--   jen ji odsunula na okamžik převodu.
--
-- CLOSURE se nemění — chk_appointments_closure_empty mu vozidlo naopak zakazuje.
--
-- Data: existující objednávky bez vozidla se mažou (rozhodnutí uživatele). Doplňovat jim auto
-- odhadem („první vozidlo zákazníka") by do evidence vneslo údaj, který nikdo nepotvrdil.
-- Pozor: kdyby mezi nimi byla převedená objednávka, zmizí i stopa, že zakázka vznikla
-- z objednávky — zakázka sama zůstane, vazba je jen na této straně (order_id).
-- =============================================================================

DELETE FROM schedule.appointments
WHERE entry_type = 'BOOKING'
  AND vehicle_id IS NULL;

ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_booking_vehicle
        CHECK (entry_type <> 'BOOKING' OR vehicle_id IS NOT NULL);

COMMENT ON COLUMN schedule.appointments.vehicle_id IS
    'Vozidlo, které přijede. U BOOKING povinné (V78), u CLOSURE musí být NULL.';
