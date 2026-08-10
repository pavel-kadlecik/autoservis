-- =============================================================================
-- V17__add_draft_status_to_invoice.sql
-- Přidává DRAFT jako první hodnotu ENUMu invoice_status a nastavuje ho
-- jako nový výchozí stav nově vytvářených faktur.
-- flyway:noAutoCommit
-- =============================================================================

ALTER TYPE billing.invoice_status ADD VALUE IF NOT EXISTS 'DRAFT' BEFORE 'ISSUED';

COMMIT;

ALTER TABLE billing.invoices
    ALTER COLUMN status SET DEFAULT 'DRAFT';
