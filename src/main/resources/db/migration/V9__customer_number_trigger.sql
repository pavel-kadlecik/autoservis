-- =============================================================================
-- V9__customer_number_trigger.sql
-- Trigger, který při INSERTu automaticky vygeneruje zákaznické číslo.
-- Formát: ZNK-{rok}-{4místné pořadí ze sekvence customer_number_seq}
-- =============================================================================

CREATE OR REPLACE FUNCTION customer.fn_generate_customer_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.customer_number :=
        'ZNK-'
        || EXTRACT(YEAR FROM NOW())::TEXT
        || '-'
        || LPAD(nextval('customer.customer_number_seq')::TEXT, 4, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_generate_customer_number
    BEFORE INSERT ON customer.customers
    FOR EACH ROW
    WHEN (NEW.customer_number IS NULL OR NEW.customer_number = '')
    EXECUTE FUNCTION customer.fn_generate_customer_number();
