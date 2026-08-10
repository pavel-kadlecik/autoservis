-- =============================================================================
-- V8__seed_vehicles_and_orders.sql
-- Seed: vozidla a servisní zakázky.
-- customer_id odkazuje jen na zákazníky 1, 2 a 3 ze seedu V3.
-- =============================================================================

-- =============================================================================
-- Vozidla
-- =============================================================================
INSERT INTO vehicle.vehicles (id, customer_id, vin, license_plate, brand, model, year_of_manufacture, first_registration_date, fuel_type, transmission, engine_displacement_ccm, engine_power_kw, color, current_mileage_km, created_by)
VALUES
    (1,  1, 'WBA3A5C50DF595551', '1AB 2345', 'BMW',        '3 Series',       2018, '2018-04-10', 'PETROL',        'AUTOMATIC', 1998, 135, 'černá',            85000,  1),
    (2,  3, 'TMBKG6NW2L7234565', '2CD 3451', 'Škoda',      'Fabia',          2020, '2020-05-12', 'PETROL',        'MANUAL',    1498,  70, 'šedá',             52000,  1),
    (3,  1, 'WVWZZZ3CZHE345678', '3EF 4567', 'Volkswagen', 'Passat',         2017, '2017-06-30', 'DIESEL',        'AUTOMATIC', 1968, 140, 'černá metalíza',  198400,  1),
    (4,  2, 'WBA5J7106K7456789', '4GH 5678', 'BMW',        '320d',           2019, '2019-03-12', 'DIESEL',        'AUTOMATIC', 1995, 140, 'bílá',             88200,  1),
    (5,  2, 'WBAJC2105M9567890', '5JK 6789', 'BMW',        'X5',             2021, '2021-11-08', 'DIESEL',        'AUTOMATIC', 2993, 210, 'tmavě šedá',       42100,  1),
    (6,  2, 'WBY8P2105N1678901', '6LM 7890', 'BMW',        'i3',             2022, '2022-05-17', 'ELECTRIC',      'AUTOMATIC', NULL, 125, 'bílá',             21500,  1),
    (7,  3, 'KMHD35LE6KU789012', '7NP 8901', 'Hyundai',   'i30',            2019, '2019-07-04', 'PETROL',        'MANUAL',    1368,  88, 'červená',         105600,  1),
    (8,  1, 'WF0XXXTTGXLP12345', '8RS 9012', 'Ford',       'Transit',        2020, '2020-02-19', 'DIESEL',        'MANUAL',    1995, 125, 'bílá',            175200,  1),
    (9,  1, 'WF05XXGCB5JY23456', '9TV 0123', 'Ford',       'Focus',          2018, '2018-10-25', 'PETROL',        'MANUAL',    1499, 110, 'modrá',           132450,  1),
    (10, 1, 'WDB9066331S345678', '1WX 1234', 'Mercedes',   'Sprinter',       2021, '2021-04-11', 'DIESEL',        'MANUAL',    2143, 120, 'bílá',             95800,  1),
    (11, 1, 'VF1MA000164567890', '2YZ 2345', 'Renault',    'Master',         2019, '2019-08-29', 'DIESEL',        'MANUAL',    2299, 110, 'stříbrná',        158300,  1),
    (12, 2, 'JTDBR32E7N0567891', '3AB 3456', 'Toyota',     'Corolla',        2022, '2022-03-08', 'HYBRID_PETROL', 'CVT',       1798,  90, 'perleťově bílá',   32400,  1),
    (13, 2, 'JTMBJREV5PD678912', '4CD 4567', 'Toyota',     'RAV4',           2023, '2023-09-15', 'HYBRID_PETROL', 'CVT',       2487, 160, 'tmavě modrá',      18900,  1),
    (14, 1, 'YV1FW7752HB890127', '5EF 5678', 'Volvo',      'V60',            2017, '2017-08-14', 'DIESEL',        'AUTOMATIC', 1969, 140, 'červená',          94200,  1),
    (15, 3, 'WV2ZZZ2KZJX891234', '6GH 6789', 'Volkswagen','Caddy',          2019, '2019-05-23', 'DIESEL',        'MANUAL',    1968,  75, 'bílá',            145600,  1),
    (16, 3, 'WV2ZZZ7HZLH912345', '7JK 7890', 'Volkswagen','Transporter T6', 2020, '2020-09-04', 'DIESEL',        'MANUAL',    1968, 110, 'bílá',            112300,  1),
    (17, 3, 'TMBJB7NE5M2123450', '8LM 8901', 'Škoda',      'Superb',        2021, '2021-06-17', 'DIESEL',        'DCT',       1968, 140, 'černá metalíza',   82900,  1),
    (18, 3, 'TMBLB7NS6N3234561', '9NP 9012', 'Škoda',      'Kodiaq',        2022, '2022-12-01', 'PETROL',        'DCT',       1984, 140, 'tmavě šedá',       45200,  1),
    (19, 2, 'JMZKE2W7A00456783', '2TV 1234', 'Mazda',      'CX-5',          2020, '2020-08-26', 'PETROL',        'AUTOMATIC', 1998, 121, 'sodalitově modrá', 68500,  1),
    (20, 1, '5YJ3E1EA6PF678905', '4YZ 3456', 'Tesla',      'Model 3',       2023, '2023-07-21', 'ELECTRIC',      'AUTOMATIC', NULL, 208, 'pearl white',      15400,  1);

SELECT setval('vehicle.vehicles_id_seq', (SELECT MAX(id) FROM vehicle.vehicles));

-- =============================================================================
-- Servisní zakázky
-- customer_id a vehicle_id musí být konzistentní s vozidly výše.
-- =============================================================================
INSERT INTO "order".orders (order_number, customer_id, vehicle_id, status, description, internal_note, estimated_completion_at, estimated_price, final_price, created_by)
VALUES
    ('ZAK-2025-0001', 3, 2,  'COMPLETED',         'Výměna oleje a filtrů, servisní prohlídka',            NULL,                                '2025-02-15 16:00:00+01', 3500,  3200,  1),
    ('ZAK-2025-0002', 1, 3,  'COMPLETED',         'Oprava klimatizace — doplnění chladiva',               NULL,                                '2025-03-10 16:00:00+01', 2800,  2800,  2),
    ('ZAK-2025-0003', 2, 4,  'COMPLETED',         'Výměna předních brzdových destiček a kotoučů',         'Zákazník přišel bez objednání.',    '2025-05-05 16:00:00+01', 4200,  4100,  2),
    ('ZAK-2025-0004', 1, 8,  'COMPLETED',         'Diagnostika — kontrolka motoru, čištění EGR ventilu',  NULL,                                '2025-07-22 16:00:00+01', 6000,  5800,  1),
    ('ZAK-2025-0005', 3, 15, 'COMPLETED',         'Přezutí na zimní pneumatiky + vyvážení',               'Faktura vystavena.',                '2025-10-20 16:00:00+01', 2400,  2400,  2),
    ('ZAK-2025-0006', 2, 12, 'COMPLETED',         'Výměna rozvodového řemene a vodní pumpy',              'Kritická oprava — vůz byl odtažen.','2025-09-05 16:00:00+01', 9500,  9500,  1),
    ('ZAK-2026-0001', 3, 7,  'RECEIVED',          'Výměna spojky — zákazník přišel dnes ráno',            'Čeká na termín.',                   '2026-06-01 16:00:00+02', 12000, NULL,  2),
    ('ZAK-2026-0002', 1, 9,  'DIAGNOSIS',         'Podezření na problém s turbodmychadlem, vibrace',      'Zákazník přivezl ráno.',            '2026-05-30 16:00:00+02', 15000, NULL,  1),
    ('ZAK-2026-0003', 3, 18, 'WAITING_FOR_PARTS', 'Výměna spojky — čekáme na díly od 19.5.',             'Dodání 23.5.',                      '2026-06-02 16:00:00+02', 11000, NULL,  2),
    ('ZAK-2026-0004', 2, 19, 'IN_PROGRESS',       'Oprava brzd — výměna zadních kotoučů a destiček',     NULL,                                '2026-05-22 16:00:00+02', 5500,  NULL,  1);

SELECT setval('"order".orders_id_seq', (SELECT MAX(id) FROM "order".orders));
