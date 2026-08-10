-- =============================================================================
-- V1__init_security_schema.sql
-- Schéma: security
--
-- Sdílené tabulky pro autentizaci a autorizaci.
-- Používají je všechny moduly aplikace — customer, vehicle, order atd.
-- Mezischémové FK z ostatních schémat míří na security.users(id).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS security;
SET search_path TO security;

-- =============================================================================
-- 1. ROLES
-- =============================================================================
CREATE TABLE roles (
    id          SMALLSERIAL  PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255),

    CONSTRAINT uq_roles_name UNIQUE (name)
);

COMMENT ON TABLE roles IS 'Role pro Spring Security. Hodnota name se mapuje přímo na GrantedAuthority (ROLE_ADMIN atd.).';

-- =============================================================================
-- 2. USERS
-- =============================================================================
CREATE TABLE users (
    id                      BIGSERIAL    PRIMARY KEY,
    username                VARCHAR(100) NOT NULL,
    email                   VARCHAR(255) NOT NULL,
    password_hash           VARCHAR(255) NOT NULL,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_expired     BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked      BOOLEAN      NOT NULL DEFAULT TRUE,
    credentials_non_expired BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts   SMALLINT     NOT NULL DEFAULT 0,
    last_login_at           TIMESTAMPTZ,
    password_changed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_username   UNIQUE (username),
    CONSTRAINT uq_users_email      UNIQUE (email),
    CONSTRAINT chk_users_email     CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'),
    CONSTRAINT chk_failed_attempts CHECK (failed_login_attempts >= 0)
);

COMMENT ON TABLE  users               IS 'Autentizační záznamy. Mapují se na Spring Security UserDetails.';
COMMENT ON COLUMN users.id            IS 'BIGSERIAL — Java Long. Odkazují na něj všechna ostatní schémata.';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash. Nikdy prostý text.';

-- =============================================================================
-- 3. USER_ROLES
-- =============================================================================
CREATE TABLE user_roles (
    user_id     BIGINT      NOT NULL REFERENCES security.users(id) ON DELETE CASCADE,
    role_id     SMALLINT    NOT NULL REFERENCES security.roles(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by BIGINT      REFERENCES security.users(id) ON DELETE SET NULL,

    PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE  user_roles             IS 'Přiřazení rolí uživatelům.';
COMMENT ON COLUMN user_roles.assigned_by IS 'FK -> security.users.id — kdo roli přiřadil (auditní stopa).';

-- =============================================================================
-- Indexy
-- =============================================================================
CREATE INDEX idx_users_email    ON users (email);
CREATE INDEX idx_users_username ON users (username);
CREATE INDEX idx_users_enabled  ON users (enabled) WHERE enabled = TRUE;

-- =============================================================================
-- Trigger: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION security.fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- =============================================================================
-- 4. TOKEN_BLACKLIST
-- Uchovává zneplatněné JWT access tokeny do jejich přirozené expirace.
-- Záznamy pravidelně uklízí BlacklistCleanupService.
-- =============================================================================
CREATE TABLE IF NOT EXISTS token_blacklist (
    token          VARCHAR(512) PRIMARY KEY,
    invalidated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE token_blacklist IS 'JWT tokeny na blacklistu (odhlášené relace).';

-- =============================================================================
-- 5. REFRESH_TOKENS
-- Serverové úložiště refresh tokenů — umožňuje okamžité zneplatnění bez
-- čekání na expiraci JWT. Jeden uživatel může mít více aktivních tokenů
-- (webový prohlížeč + mobilní aplikace současně).
--
-- Tokeny se nikdy nemažou, pouze revokují. To umožňuje detekci útoků
-- opakovaným použitím tokenu: použití již revokovaného tokenu signalizuje možné zcizení.
-- =============================================================================
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         VARCHAR(36)  PRIMARY KEY,
    token      VARCHAR(36)  NOT NULL UNIQUE,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP    NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id
    ON refresh_tokens (user_id);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_revoked
    ON refresh_tokens (revoked)
    WHERE revoked = FALSE;
