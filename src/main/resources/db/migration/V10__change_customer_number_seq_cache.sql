-- =============================================================================
-- V10__change_customer_number_seq_cache.sql
-- Snižuje cache sekvence customer_number_seq na 1, aby nevznikaly mezery v zákaznických číslech.
-- =============================================================================

ALTER SEQUENCE customer.customer_number_seq CACHE 1;
