-- =============================================================================
-- V73__seed_appointments.sql
-- Umístění: db/demo — POUZE dev/local a testy, nikdy produkce.
--
-- Ukázkové objednávky a blokace pro plánovací kalendář.
-- Datumy jsou relativní k NOW(), aby demo nezestárlo — kalendář se má po seedu
-- otevřít na aktuálním týdnu a něco v něm být.
-- =============================================================================

INSERT INTO schedule.appointments
(entry_type, title, note, starts_at, ends_at, customer_id, vehicle_id, status)
VALUES

-- 1) Dnes dopoledne, potvrzená objednávka
('BOOKING',
 'Výměna oleje a filtrů',
 'Zákazník počká v čekárně.',
 date_trunc('day', NOW()) + INTERVAL '9 hours',
 date_trunc('day', NOW()) + INTERVAL '10 hours',
 (SELECT id FROM customer.customers WHERE first_name = 'Jan' AND last_name = 'Novák'),
 (SELECT id FROM vehicle.vehicles  WHERE license_plate = '1AB 2345'),
 'CONFIRMED'),

-- 2) Dnes odpoledne, naplánovaná objednávka
('BOOKING',
 'Výměna brzdových destiček',
 'Zákazník zaplatí hotově.',
 date_trunc('day', NOW()) + INTERVAL '13 hours',
 date_trunc('day', NOW()) + INTERVAL '15 hours',
 (SELECT id FROM customer.customers WHERE first_name = 'Jan' AND last_name = 'Novák'),
 (SELECT id FROM vehicle.vehicles  WHERE license_plate = '3EF 4567'),
 'PLANNED'),

-- 3) Zítra dopoledne, naplánovaná objednávka
('BOOKING',
 'Příprava na STK',
 'Vyměnit žárovky.',
 date_trunc('day', NOW()) + INTERVAL '1 day 8 hours',
 date_trunc('day', NOW()) + INTERVAL '1 day 12 hours',
 (SELECT id FROM customer.customers WHERE first_name = 'Jan' AND last_name = 'Novák'),
 (SELECT id FROM vehicle.vehicles  WHERE license_plate = '8RS 9012'),
 'PLANNED'),

-- 4) Blokace dílny — pozítří celý den (00:00 až 00:00 dalšího dne);
--    CLOSURE nemá zákazníka ani vozidlo, hlídá to CHECK.
('CLOSURE',
 'Školení techniků',
 NULL,
 date_trunc('day', NOW()) + INTERVAL '2 days',
 date_trunc('day', NOW()) +  INTERVAL '3 days',
 NULL,
 NULL,
 'PLANNED'),

-- 5) Minulý týden — zákazník nedorazil
('BOOKING',
 'Diagnostika motoru',
 'Zákazník se neozval, nedorazil.',
 date_trunc('day', NOW()) - INTERVAL '5 days' + INTERVAL '14 hours',
 date_trunc('day', NOW()) - INTERVAL '5 days' + INTERVAL '15 hours',
 (SELECT id FROM customer.customers WHERE first_name = 'Jan' AND last_name = 'Novák'),
 (SELECT id FROM vehicle.vehicles  WHERE license_plate = '9TV 0123'),
 'NO_SHOW');