-- =============================================================================
-- V31__add_invoice_payment_method_type.sql
-- Schéma: billing
-- Přidává do ENUM typu platební metody hodnoty CASH_OR_TRANSFER, CASH_OR_CARD, CARD_OR_TRANSFER.
-- =============================================================================

ALTER TYPE billing.payment_method
    ADD VALUE IF NOT EXISTS 'CASH_OR_TRANSFER';

ALTER TYPE billing.payment_method
    ADD VALUE IF NOT EXISTS 'CASH_OR_CARD';

ALTER TYPE billing.payment_method
    ADD VALUE IF NOT EXISTS 'CARD_OR_TRANSFER';