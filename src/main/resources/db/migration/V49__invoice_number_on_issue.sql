-- =============================================================================
-- V49__invoice_number_on_issue.sql
-- Schéma: billing
--
-- Číslo faktury (a variabilní symbol) se nově přiděluje až při VYSTAVENÍ
-- (DRAFT → ISSUED), ne při založení konceptu, a odvozuje se z issue_date, ne
-- z CURRENT_DATE (audit K-3 / R-6). Tím:
--   1) číslo řady sedí s datem vystavení dokladu (dřív se lišilo o měsíc),
--   2) koncepty (DRAFT) nespotřebovávají čísla řady → vystavená řada je souvislá,
--   3) koncept bez čísla je zjevně „ještě ne doklad" (podporuje vodoznak NÁVRH, A3).
--
-- Sloupce invoice_number a variable_symbol jsou proto nullable (koncept je nemá).
-- Trigger se přesouvá z BEFORE INSERT na podmíněný BEFORE UPDATE (jen přechod do
-- ISSUED). Advisory lock per měsíc zůstává; přidán guard proti přetečení řady
-- (>999/měsíc — dřív tichá kolize, audit A9).
-- =============================================================================

SET search_path TO billing;

ALTER TABLE billing.invoices ALTER COLUMN invoice_number  DROP NOT NULL;
ALTER TABLE billing.invoices ALTER COLUMN variable_symbol DROP NOT NULL;

CREATE OR REPLACE FUNCTION billing.fn_generate_invoice_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year_month VARCHAR(6);
    v_next_seq   INTEGER;
BEGIN
    IF NEW.invoice_number IS NOT NULL THEN
        RETURN NEW;
    END IF;

    -- Prefix z data VYSTAVENÍ (issue_date), ne z dnešního data.
    v_year_month := TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM');

    -- Advisory lock per měsíc — souběžné transakce čekají, místo aby soupeřily.
    PERFORM pg_advisory_xact_lock(hashtext('billing.invoices.' || v_year_month));

    SELECT COALESCE(MAX(CAST(SUBSTRING(invoice_number FROM 7) AS INTEGER)), 0) + 1
    INTO v_next_seq
    FROM billing.invoices
    WHERE invoice_number LIKE v_year_month || '%';

    IF v_next_seq > 999 THEN
        RAISE EXCEPTION 'Invoice number sequence overflow for % (>999 invoices in one month)', v_year_month;
    END IF;

    NEW.invoice_number := v_year_month || LPAD(v_next_seq::TEXT, 3, '0');

    IF NEW.variable_symbol IS NULL THEN
        NEW.variable_symbol := NEW.invoice_number;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_invoices_generate_number ON billing.invoices;

CREATE TRIGGER trg_invoices_generate_number
    BEFORE UPDATE ON billing.invoices
    FOR EACH ROW
    WHEN (NEW.status = 'ISSUED'::billing.invoice_status
          AND OLD.status <> 'ISSUED'::billing.invoice_status
          AND NEW.invoice_number IS NULL)
    EXECUTE FUNCTION billing.fn_generate_invoice_number();
