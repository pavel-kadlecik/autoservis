-- =============================================================================
-- V64__add_user_lock_expiry.sql
-- Schéma: security
--
-- Časová expirace zámku účtu (audit 2026-07-30, nález KN-5).
--
-- Dosud byl zámek po 10 neúspěšných přihlášeních TRVALÝ: `lockAccount` nastavil
-- `account_non_locked = FALSE` a odemknout ho umělo výhradně ADMIN-only
-- `POST /users/{id}/reset-password`. Produkční seed (db/prod/V60) má jediný účet
-- `admin`, takže kdokoli z internetu ho 10 požadavky na veřejný `/auth/login`
-- natrvalo vyřadil a obnova vyžadovala ruční UPDATE v databázi.
--
-- Sloupec `locked_at` říká, KDY byl zámek nasazen. Aplikace pak před autentizací
-- zámek uvolní, pokud už uplynula konfigurovaná lhůta (`lockout.duration`).
-- Samotné rozhodnutí o lhůtě zůstává v aplikaci, ne v DB — je to provozní
-- nastavení, ne vlastnost schématu.
-- =============================================================================

SET search_path TO security;

ALTER TABLE security.users
    ADD COLUMN locked_at TIMESTAMPTZ;

COMMENT ON COLUMN security.users.locked_at IS
    'Kdy byl účet uzamčen po překročení počtu neúspěšných přihlášení (NULL = není zamčeno). Od této hodnoty se počítá expirace zámku (lockout.duration). Audit KN-5.';

-- Backfill: účty zamčené před touto migrací nemají `locked_at`, takže by je
-- guardovaný UPDATE v aplikaci (podmínka `locked_at IS NOT NULL`) nikdy neuvolnil
-- a trvalý zámek by pro ně přetrval — přesně to, co migrace opravuje.
-- Stampujeme NOW(), takže jim zámek vyprší po standardní lhůtě od nasazení.
-- Záměrně je NEodemykáme rovnou: chování má být pro všechny účty stejné a
-- předvídatelné; kdo potřebuje odemknout ihned, má dál admin reset hesla.
UPDATE security.users
SET locked_at = NOW()
WHERE account_non_locked = FALSE;
