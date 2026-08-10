-- =============================================================================
-- V26__rename_summary_service_columns.sql
-- Schéma: order
-- Sladění názvů sloupců view s entitou OrderItemSummary (services_* -> service_*).
-- Závisí na V25.
-- =============================================================================

ALTER VIEW "order".v_order_item_summary RENAME COLUMN services_net   TO service_net;
ALTER VIEW "order".v_order_item_summary RENAME COLUMN services_gross TO service_gross;