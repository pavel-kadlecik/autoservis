-- =============================================================================
-- V3__seed_initial_data.sql
-- Schémata: security, customer
--
-- BCrypt hash hesla "Password1!" — PŘED PRODUKCÍ ZMĚNIT.
-- Vygenerováno pomocí: new BCryptPasswordEncoder().encode("Password1!")
--
-- Po vložení řádků s explicitními BIGINT id se musí BIGSERIAL sekvence
-- synchronizovat, aby první aplikační INSERT nenarazil na konflikt unikátního
-- klíče. Viz volání setval() na konci tohoto souboru.
-- =============================================================================

SET search_path TO security;

-- =============================================================================
-- Role
-- =============================================================================
INSERT INTO security.roles (name, description) VALUES
    ('ROLE_ADMIN',    'Administrátor systému — plný přístup'),
    ('ROLE_MANAGER',  'Vedoucí servisu — správa zákazníků, zakázek, reportů'),
    ('ROLE_MECHANIC', 'Mechanik / technik — přístup k přiděleným zakázkám'),
    ('ROLE_CUSTOMER', 'Zákazník — přístup do zákaznického portálu'),
    ('ROLE_READONLY', 'Pouze čtení — pro reportovací nástroje')
ON CONFLICT (name) DO NOTHING;

-- =============================================================================
-- Účty personálu
-- =============================================================================
INSERT INTO security.users (id, username, email, password_hash, enabled, account_non_expired, account_non_locked, credentials_non_expired)
VALUES
    (1, 'admin',    'admin@autoservis.cz',    '$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky', TRUE, TRUE, TRUE, TRUE),
    (2, 'manager',  'manager@autoservis.cz',  '$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky', TRUE, TRUE, TRUE, TRUE),
    (3, 'mechanic', 'mechanic@autoservis.cz', '$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky', TRUE, TRUE, TRUE, TRUE);

-- Účty zákaznického portálu
INSERT INTO security.users (id, username, email, password_hash)
VALUES
    (10, 'jan.novak',       'jan.novak@email.cz',      '$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky'),
    (11, 'firma.logistika', 'servis@logistika-abc.cz', '$2a$12$RfJPRJHqbKmHRfJwQqJyVeCGtsiVTZG5b3uEYofjI0dtKI3Mc51ky');

-- =============================================================================
-- Přiřazení rolí
-- =============================================================================
INSERT INTO security.user_roles (user_id, role_id, assigned_by)
SELECT 1, id, NULL FROM security.roles WHERE name = 'ROLE_ADMIN';

INSERT INTO security.user_roles (user_id, role_id, assigned_by)
SELECT 2, id, 1 FROM security.roles WHERE name = 'ROLE_MANAGER';

INSERT INTO security.user_roles (user_id, role_id, assigned_by)
SELECT 3, id, 1 FROM security.roles WHERE name = 'ROLE_MECHANIC';

INSERT INTO security.user_roles (user_id, role_id, assigned_by)
SELECT 10, id, 1 FROM security.roles WHERE name = 'ROLE_CUSTOMER';

INSERT INTO security.user_roles (user_id, role_id, assigned_by)
SELECT 11, id, 1 FROM security.roles WHERE name = 'ROLE_CUSTOMER';

-- =============================================================================
-- Zákazníci
-- =============================================================================
SET search_path TO customer;

-- Zákazník 1: Fyzická osoba S portálovým účtem (user_id = 10)
INSERT INTO customer.customers (
    id, user_id, customer_type, customer_number,
    first_name, last_name, birth_date,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel, created_by
) VALUES (
    1, 10, 'INDIVIDUAL', 'ZNK-2025-0001',
    'Jan', 'Novák', '1985-03-15',
    'jan.novak@email.cz', '+420 603 111 222',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL', 2
);

-- Zákazník 2: Fyzická osoba BEZ portálového účtu
INSERT INTO customer.customers (
    id, user_id, customer_type, customer_number,
    first_name, last_name, birth_date,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    internal_note, created_by
) VALUES (
    2, NULL, 'INDIVIDUAL', 'ZNK-2025-0002',
    'Marie', 'Svobodová', '1972-08-20',
    'marie.svobodova@seznam.cz', '+420 721 333 444',
    TRUE, NOW(),
    'Zákaznice preferuje odpolední termíny.', 2
);

-- Zákazník 3: Firma s portálovým účtem (user_id = 11)
INSERT INTO customer.customers (
    id, user_id, customer_type, customer_number,
    company_name, ico, dic, legal_form,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel, internal_note,
    loyalty_points, created_by
) VALUES (
    3, 11, 'COMPANY', 'ZNK-2025-0003',
    'Logistika ABC s.r.o.', '12345678', 'CZ12345678', 's.r.o.',
    'servis@logistika-abc.cz', '+420 511 555 666',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL',
    'Firemní zákazník — flotila 8 vozidel. Faktura vždy do 30 dní.',
    500, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    first_name, last_name, birth_date,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel, loyalty_points, created_by
) VALUES (
    4, 'INDIVIDUAL', 'ZNK-2025-0004',
    'Petr', 'Novotný', '1990-05-12',
    'petr.novotny@gmail.com', '+420 601 234 567',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL', 0, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    first_name, last_name, birth_date,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    preferred_contact_channel,
    internal_note, loyalty_points, created_by
) VALUES (
    5, 'INDIVIDUAL', 'ZNK-2025-0005',
    'Jana', 'Procházková', '1988-11-03',
    'jana.prochazkova@seznam.cz', '+420 702 345 678',
    TRUE, NOW(), 'PHONE',
    'Preferuje ranní termíny.', 100, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    company_name, ico, dic, legal_form,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel,
    internal_note, loyalty_points, created_by
) VALUES (
    6, 'COMPANY', 'ZNK-2025-0006',
    'Stavební firma Horák s.r.o.', '23456781', 'CZ23456781', 's.r.o.',
    'info@horak-stavby.cz', '+420 511 234 567',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL',
    'Flotila 3 vozidel. Platí hotově.', 150, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    company_name, ico, dic, legal_form,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel,
    internal_note, loyalty_points, created_by
) VALUES (
    7, 'COMPANY', 'ZNK-2025-0007',
    'Doprava Novák a.s.', '23456782', 'CZ23456782', 'a.s.',
    'provoz@doprava-novak.cz', '+420 512 345 678',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL',
    'Velký firemní zákazník — 15 vozidel.', 1000, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    company_name, ico, dic, legal_form,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel, loyalty_points, created_by
) VALUES (
    8, 'COMPANY', 'ZNK-2025-0008',
    'IT Solutions Praha s.r.o.', '23456784', 'CZ23456784', 's.r.o.',
    'servis@itsolutions.cz', '+420 514 567 890',
    TRUE, NOW(), TRUE, NOW(), 'EMAIL', 200, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    company_name, ico, dic, legal_form,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    preferred_contact_channel, loyalty_points, created_by
) VALUES (
    9, 'COMPANY', 'ZNK-2025-0009',
    'Pekárna Zlatý květ s.r.o.', '23456783', 'CZ23456783', 's.r.o.',
    'info@zlatykvet.cz', '+420 513 456 789',
    TRUE, NOW(), 'PHONE', 0, 2
);

INSERT INTO customer.customers (
    id, customer_type, customer_number,
    first_name, last_name, birth_date,
    primary_email, primary_phone,
    gdpr_consent, gdpr_consent_at,
    marketing_consent, marketing_consent_at,
    preferred_contact_channel,
    internal_note, loyalty_points, created_by
) VALUES (
    10, 'INDIVIDUAL', 'ZNK-2025-0010',
    'Radek', 'Šimánek', '1971-05-04',
    'radek.simanek@volny.cz', '+420 608 012 345',
    TRUE, NOW(), TRUE, NOW(), 'PHONE',
    'Zákazník má 2 vozidla.', 600, 2
);

-- =============================================================================
-- Adresy
-- =============================================================================
INSERT INTO customer.addresses (id, customer_id, address_type, is_default, street, street_number, city, postal_code)
VALUES
    (1, 1, 'CONTACT',      TRUE, 'Hlavní',     '42',  'Brno',    '602 00'),
    (2, 1, 'BILLING',      TRUE, 'Hlavní',     '42',  'Brno',    '602 00'),
    (3, 2, 'CONTACT',      TRUE, 'Lipová',     '8',   'Praha 2', '120 00'),
    (4, 3, 'HEADQUARTERS', TRUE, 'Průmyslová', '100', 'Ostrava', '702 00'),
    (5, 3, 'BILLING',      TRUE, 'Průmyslová', '100', 'Ostrava', '702 00');

-- =============================================================================
-- Kontaktní osoby (firemní zákazník)
-- =============================================================================
INSERT INTO customer.contact_persons (id, customer_id, first_name, last_name, position, email, phone, is_primary)
VALUES
    (1, 3, 'Petr', 'Kovář',       'Fleet Manager', 'p.kovar@logistika-abc.cz',   '+420 603 777 888', TRUE),
    (2, 3, 'Jana', 'Procházková', 'Účetní',        'fakturace@logistika-abc.cz', '+420 603 999 000', FALSE);

-- =============================================================================
-- Ukázka komunikace
-- =============================================================================
INSERT INTO customer.customer_communications (id, customer_id, channel, direction, subject, body, handled_by)
VALUES (
    1, 3, 'EMAIL', 'INBOUND',
    'Objednávka servisu flotily',
    'Dobrý den, prosím o objednání servisu pro 3 vozidla na příští týden.',
    2
);

-- =============================================================================
-- Synchronizace sekvencí
-- Po vložení řádků s explicitními id se sekvence posunou za nejvyšší vloženou
-- hodnotu, aby první aplikační INSERT nenarazil na konflikt unikátního klíče.
-- =============================================================================
SELECT setval('security.users_id_seq',                   (SELECT MAX(id) FROM security.users));
SELECT setval('customer.customers_id_seq',               (SELECT MAX(id) FROM customer.customers));
SELECT setval('customer.addresses_id_seq',               (SELECT MAX(id) FROM customer.addresses));
SELECT setval('customer.contact_persons_id_seq',         (SELECT MAX(id) FROM customer.contact_persons));
SELECT setval('customer.customer_communications_id_seq', (SELECT MAX(id) FROM customer.customer_communications));
