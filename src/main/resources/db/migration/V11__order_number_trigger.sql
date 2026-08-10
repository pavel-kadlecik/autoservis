-- =============================================================================
-- V11__order_number_trigger.sql
-- Trigger, který při INSERTu automaticky vygeneruje číslo zakázky.
-- Formát: ZAK-{rok}-{4místné pořadí ze sekvence order_number_seq}
-- =============================================================================

CREATE SEQUENCE "order".order_number_seq
    START WITH 11
    INCREMENT BY 1
    NO MAXVALUE
    CACHE 1;

CREATE OR REPLACE FUNCTION "order".fn_generate_order_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.order_number :=
        'ZAK-'
        || EXTRACT(YEAR FROM NOW())::TEXT
        || '-'
        || LPAD(nextval('"order".order_number_seq')::TEXT, 4, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_generate_order_number
    BEFORE INSERT ON "order".orders
    FOR EACH ROW
    WHEN (NEW.order_number IS NULL OR NEW.order_number = '')
    EXECUTE FUNCTION "order".fn_generate_order_number();
