-- =============================================================================
-- V45__widen_refresh_token_for_hash.sql
-- Schéma: security
--
-- Refresh tokeny se nově ukládají jako SHA-256 hex digest (64 znaků) místo
-- surového neprůhledného UUID (audit K-7), zrcadlí token_blacklist (V4). Uniklá
-- záloha DB tak už neposkytne použitelné refresh tokeny. Sloupec se rozšiřuje,
-- aby se hash vešel (dosud VARCHAR(36) pro UUID).
--
-- Existující řádky se surovým tokenem po nasazení přestanou odpovídat (vyhledávání
-- hashuje vstup) — dotčení uživatelé se prostě přihlásí znovu; access tokeny fungují
-- dál do přirozené expirace.
-- =============================================================================

SET search_path TO security;

ALTER TABLE security.refresh_tokens
    ALTER COLUMN token TYPE VARCHAR(64);
