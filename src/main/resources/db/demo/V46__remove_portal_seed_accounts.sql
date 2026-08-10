-- =============================================================================
-- V46__remove_portal_seed_accounts.sql
-- Schéma: security
--
-- Zákaznický portál (ROLE_CUSTOMER) zatím neexistuje. Seed účty
-- jan.novak (id 10) a firma.logistika (id 11) nesou ROLE_CUSTOMER a sdílené
-- seed heslo; přes plochou autorizaci /api/** by viděly a editovaly celou firmu
-- (audit K-10 / R-4). Role se jim odebírá a přihlášení se vypíná.
--
-- Řádky uživatelů se ponechávají, nemažou (filozofie nemazání; customer.customers
-- na ně odkazuje přes ON DELETE SET NULL). Pokud portál někdy vznikne, stačí je
-- znovu povolit. SecurityConfig na backendu navíc jako obranu do hloubky odpírá
-- ROLE_CUSTOMER přístup na /api/**.
-- =============================================================================

DELETE FROM security.user_roles WHERE user_id IN (10, 11);

UPDATE security.users SET enabled = FALSE WHERE id IN (10, 11);
