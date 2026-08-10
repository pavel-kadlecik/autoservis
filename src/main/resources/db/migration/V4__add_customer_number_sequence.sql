-- =============================================================================
-- V4__add_customer_number_sequence.sql
-- Schéma: customer
--
-- Sekvence pro generování zákaznických čísel.
-- Bezpečná pro provoz více instancí aplikace (horizontální škálování).
--
-- Formát zákaznického čísla: ZNK-{rok}-{4místné pořadí}
-- Příklad: ZNK-2025-0042
-- =============================================================================

SET search_path TO customer;

CREATE SEQUENCE IF NOT EXISTS customer.customer_number_seq
    START WITH 4        -- začíná za seed daty (ZNK-2025-0001 až 0003)
    INCREMENT BY 1
    NO MAXVALUE
    CACHE 20;

COMMENT ON SEQUENCE customer.customer_number_seq IS
    'Sekvence pro generování zákaznických čísel. Použití: SELECT nextval(''customer.customer_number_seq'')';
