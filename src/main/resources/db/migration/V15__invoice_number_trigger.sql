-- =============================================================================
-- V15__invoice_number_trigger.sql
-- Trigger, který při INSERTu automaticky vygeneruje invoice_number a variable_symbol.
-- Formát: YYYYMM + 3místné pořadí v rámci daného měsíce (např. 202501001)
-- Používá advisory lock, aby při souběžném zápisu nevznikla duplicitní čísla.
-- =============================================================================

CREATE OR REPLACE FUNCTION billing.fn_generate_invoice_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year_month VARCHAR(6);
    v_next_seq   INTEGER;
BEGIN
    IF NEW.invoice_number IS NOT NULL THEN
        RETURN NEW;
    END IF;

    v_year_month := TO_CHAR(CURRENT_DATE, 'YYYYMM');

    -- Advisory lock na měsíc — souběžné transakce čekají, místo aby závodily.
    PERFORM pg_advisory_xact_lock(hashtext('billing.invoices.' || v_year_month));

    SELECT COALESCE(MAX(CAST(SUBSTRING(invoice_number FROM 7) AS INTEGER)), 0) + 1
    INTO v_next_seq
    FROM billing.invoices
    WHERE invoice_number LIKE v_year_month || '%';

    NEW.invoice_number := v_year_month || LPAD(v_next_seq::TEXT, 3, '0');

    IF NEW.variable_symbol IS NULL THEN
        NEW.variable_symbol := NEW.invoice_number;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoices_generate_number
    BEFORE INSERT ON billing.invoices
    FOR EACH ROW
    EXECUTE FUNCTION billing.fn_generate_invoice_number();
