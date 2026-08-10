-- =============================================================================
-- V85__appointment_optional_customer.sql
-- Schéma: schedule
--
-- Objednávka (BOOKING) už nemusí mít zákazníka ani vozidlo. Přibývá volitelný
-- kontakt na neevidovaného zákazníka.
--
-- Proč (rozhodnutí uživatele 2026-08-07):
--   Vazba na zákazníka a vozidlo byla pro obsluhu svazující. Termín se domlouvá po
--   telefonu dřív, než servis o autě cokoli ví: „přijedu ve středu ráno, něco to
--   klepe" — značku ani SPZ volající často neřekne a sám v evidenci být nemusí.
--   Vynucená vazba nutila zakládat zákazníka i vozidlo z odhadu, tedy zapsat do
--   evidence údaj, který nikdo nepotvrdil. To je horší než prázdné pole.
--
--   Tím se vrací stav před V78 (vozidlo) a V72 (zákazník). Zdůvodnění V78 („zakázka
--   vozidlo stejně vyžaduje, objednávka bez něj práci jen odsouvá") platí dál, ale
--   míří na špatný okamžik: vozidlo je potřeba, až auto přijede a vzniká zakázka —
--   ne když se domlouvá termín. Převod na zakázku si vozidlo vyžádá sám.
--
--   Objednávka zůstává čitelná i prázdná: title je NOT NULL, takže karta v kalendáři
--   vždy nese aspoň název práce.
--
-- contact_note drží jméno a telefon zákazníka, který v evidenci není — jinak by
-- termín nešlo s nikým přeložit. Zapisuje se volným textem: obsluha si po telefonu
-- poznamená, co stihne, a strukturovaná pole (jméno zvlášť, telefon zvlášť) by u
-- „paní Nováková, volá z práce" jen překážela. Až přibudou SMS připomínky, telefon
-- dostane vlastní sloupec.
--
-- CLOSURE ani EVENT se nemění — chk_appointments_closure_empty a
-- chk_appointments_event_empty jim zákazníka i vozidlo dál zakazují a contact_note
-- se k nim přidává týmž pravidlem níž.
--
-- Data: nic se nemaže ani nemění. Migrace jen ruší dvě omezení a přidává sloupec,
-- takže je bezpečná i na běžící produkci — všechny existující objednávky mají
-- zákazníka i vozidlo vyplněné a zůstávají platné.
-- =============================================================================

-- =============================================================================
-- Uvolnění povinných vazeb
-- =============================================================================

-- V72: BOOKING musel mít zákazníka.
ALTER TABLE schedule.appointments
    DROP CONSTRAINT chk_appointments_booking_customer;

-- V78: BOOKING musel mít vozidlo.
ALTER TABLE schedule.appointments
    DROP CONSTRAINT chk_appointments_booking_vehicle;

COMMENT ON COLUMN schedule.appointments.customer_id IS
    'Zákazník, který přijede. Volitelný (V85) — termín se domlouvá dřív, než servis '
    'zákazníka eviduje. U CLOSURE a EVENT musí být NULL.';

COMMENT ON COLUMN schedule.appointments.vehicle_id IS
    'Vozidlo, které přijede. Volitelné (V85) — po telefonu se často neví, s čím '
    'zákazník dorazí. U CLOSURE a EVENT musí být NULL.';

-- =============================================================================
-- Kontakt na neevidovaného zákazníka
-- =============================================================================

ALTER TABLE schedule.appointments
    ADD COLUMN contact_note VARCHAR(200);

COMMENT ON COLUMN schedule.appointments.contact_note IS
    'Jméno a telefon zákazníka, který není v evidenci (volný text). Jen pro BOOKING; '
    'u zákazníka navázaného přes customer_id se nevyplňuje.';

-- Kontakt patří jen k objednávce — blokace dílny ani událost nikoho neobjednávají
-- (zrcadlo chk_appointments_closure_empty a chk_appointments_event_empty).
ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_contact_booking_only
        CHECK (contact_note IS NULL OR entry_type = 'BOOKING');
