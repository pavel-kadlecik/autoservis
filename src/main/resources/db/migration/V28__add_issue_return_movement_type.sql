-- =============================================================================
-- V28__add_issue_return_movement_type.sql
-- Schéma: warehouse
-- Přidává typ pohybu ISSUE_RETURN.
-- =============================================================================

ALTER TYPE warehouse.movement_type
    ADD VALUE IF NOT EXISTS 'ISSUE_RETURN';