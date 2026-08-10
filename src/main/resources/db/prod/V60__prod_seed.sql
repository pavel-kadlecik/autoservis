-- =============================================================================
-- V60__prod_seed.sql
-- Umístění: db/prod  (spouští se POUZE v produkci — profil `prod`, viz
--            application-prod.yaml: spring.flyway.locations = db/migration,db/prod)
--
-- Produkční „bootstrap": prázdná DB + JEDEN admin. Dev/test tuto migraci nevidí
-- (jejich locations = db/migration,db/demo), demo data se seedují jen tam (V3, V8,
-- V13, V16, …). Produkce dostane jen schéma (db/migration) + tento minimální seed.
--
-- Číslování: db/prod používá celé verze navazující na db/migration; schéma migrace
-- proto pokračují od V61 (viz docs/konvence.md, skill nova-migrace).
--
-- Heslo admina se NEukládá do gitu — dodává se tajemstvím při nasazení přes env
-- ADMIN_PASSWORD_HASH → Flyway placeholder ${admin_password_hash} (BCrypt hash).
-- Po prvním přihlášení si admin heslo změní v UI (Správa uživatelů).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Role — stejná pětice jako demo seed (V3), aby autorizace i správa uživatelů
--    fungovaly identicky. Idempotentní (ON CONFLICT) — bezpečné při opětovném běhu.
--    Autorizace vyžaduje názvy s prefixem ROLE_ (SimpleGrantedAuthority = roles.name).
-- -----------------------------------------------------------------------------
INSERT INTO security.roles (name, description) VALUES
    ('ROLE_ADMIN',    'Administrátor systému — plný přístup'),
    ('ROLE_MANAGER',  'Vedoucí servisu — správa zakázek, fakturace, zákazníků'),
    ('ROLE_MECHANIC', 'Mechanik / technik — příjem zboží, práce na zakázkách'),
    ('ROLE_CUSTOMER', 'Zákazník — přístup do zákaznického portálu'),
    ('ROLE_READONLY', 'Pouze čtení')
ON CONFLICT (name) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2) Jeden admin. Bez explicitního id (BIGSERIAL → id 1 v čerstvé DB); ostatní
--    sloupce mají defaulty (enabled=TRUE, *_non_expired=TRUE, password_changed_at,
--    created_at, updated_at). Idempotentní přes UNIQUE username.
-- -----------------------------------------------------------------------------
INSERT INTO security.users (username, email, password_hash, enabled)
VALUES ('admin', 'admin@autoservis.cz', '${admin_password_hash}', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO security.user_roles (user_id, role_id)
SELECT u.id, r.id
FROM security.users u
JOIN security.roles r ON r.name = 'ROLE_ADMIN'
WHERE u.username = 'admin'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3) Číslování zákazníků od ZNK-{rok}-0001. V4 založila sekvenci se START WITH 4
--    a V47 ji posunula na 10 (obojí kvůli 10 demo zákazníkům V3, kteří v produkci
--    nejsou — V47 je navíc v db/demo, takže se v produkci ani nespustí). Reset na 1
--    (is_called=false → následující nextval vrátí 1).
--    (Prázdné employee.employees řeší produkční db/prod/V58 — žádné demo, žádný úklid.)
-- -----------------------------------------------------------------------------
SELECT setval('customer.customer_number_seq', 1, false);
