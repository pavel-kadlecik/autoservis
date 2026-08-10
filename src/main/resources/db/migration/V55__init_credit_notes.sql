-- =============================================================================
-- V55__init_credit_notes.sql
-- Schéma: billing
--
-- Opravný daňový doklad (dobropis) dle §45 zákona o DPH (audit K-8 / R-2, R-7).
-- Samostatná tabulka (ne discriminator na invoices) — izoluje dobropis od
-- fakturačního toku a nemíchá se do jeho unique/triggeru/automatu.
--
-- §45 náležitosti se skládají takto:
--   • označení „opravný daňový doklad"  → typ dokladu (PDF, E5.2)
--   • identifikace obou stran vč. DIČ   → snapshoty PŮVODNÍ faktury (invoice_party)
--   • evidenční číslo původního dokladu → FK original_invoice_id
--   • evidenční číslo opravného dokladu → credit_note_number (řada „OD", přiděl. při vystavení)
--   • důvod opravy                       → correction_reason
--   • rozdíl základu daně / daně / částky → záporné souhrny původní faktury (views, MVP = plný dobropis)
--   • datum vystavení                    → issue_date
--
-- Vlastní číselná řada `OD{YYYYMM}###` (nezaměnitelná s fakturami), přidělovaná až
-- při vystavení (DRAFT→ISSUED) — stejný vzor jako faktura po V49.
-- =============================================================================

SET search_path TO billing;

CREATE TABLE billing.credit_notes (
    id                   BIGSERIAL              PRIMARY KEY,
    credit_note_number   VARCHAR,                                    -- OD{YYYYMM}###, přiděleno při vystavení
    original_invoice_id  BIGINT                 NOT NULL,
    correction_reason    VARCHAR(500)           NOT NULL,
    issue_date           DATE                   NOT NULL DEFAULT CURRENT_DATE,
    taxable_supply_date  DATE                   NOT NULL,
    status               billing.invoice_status NOT NULL DEFAULT 'DRAFT',
    created_at           TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ            NOT NULL DEFAULT NOW(),
    created_by           BIGINT REFERENCES security.users(id) ON DELETE SET NULL,

    CONSTRAINT uq_credit_note_number UNIQUE (credit_note_number),
    CONSTRAINT fk_credit_note_invoice
        FOREIGN KEY (original_invoice_id) REFERENCES billing.invoices(id)
);

CREATE INDEX idx_credit_notes_original ON billing.credit_notes (original_invoice_id);

CREATE TRIGGER trg_credit_notes_updated_at
    BEFORE UPDATE ON billing.credit_notes
    FOR EACH ROW
    EXECUTE FUNCTION billing.fn_set_updated_at();

-- Číslo se přiděluje z data vystavení až při přechodu do ISSUED, advisory lock per měsíc.
CREATE OR REPLACE FUNCTION billing.fn_generate_credit_note_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year_month VARCHAR(6);
    v_next_seq   INTEGER;
BEGIN
    IF NEW.credit_note_number IS NOT NULL THEN
        RETURN NEW;
    END IF;

    v_year_month := TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM');
    PERFORM pg_advisory_xact_lock(hashtext('billing.credit_notes.' || v_year_month));

    SELECT COALESCE(MAX(CAST(SUBSTRING(credit_note_number FROM 9) AS INTEGER)), 0) + 1
    INTO v_next_seq
    FROM billing.credit_notes
    WHERE credit_note_number LIKE 'OD' || v_year_month || '%';

    IF v_next_seq > 999 THEN
        RAISE EXCEPTION 'Credit note number sequence overflow for % (>999 in one month)', v_year_month;
    END IF;

    NEW.credit_note_number := 'OD' || v_year_month || LPAD(v_next_seq::TEXT, 3, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_notes_generate_number
    BEFORE UPDATE ON billing.credit_notes
    FOR EACH ROW
    WHEN (NEW.status = 'ISSUED'::billing.invoice_status
          AND OLD.status <> 'ISSUED'::billing.invoice_status
          AND NEW.credit_note_number IS NULL)
    EXECUTE FUNCTION billing.fn_generate_credit_note_number();
