-- =============================================================================
-- V57__init_cash_receipts.sql
-- Schéma: billing
--
-- Příjmový pokladní doklad (PPD) — potvrzení, že pokladna přijala hotovost k úhradě
-- faktury. Náležitosti účetního dokladu dle §11 zákona o účetnictví (563/1991):
-- označení + číslo, obsah a účastníci, částka, okamžik vyhotovení, podpis.
--
-- Samostatná tabulka (jako credit_notes, V55) — izoluje pokladní doklad od
-- fakturačního toku, nemíchá se do jeho unique/triggeru/automatu.
--
-- Odlišnosti od faktury/dobropisu:
--   • Žádný životní cyklus DRAFT→ISSUED — PPD je okamžité potvrzení příjmu hotovosti,
--     číslo řady se proto přiděluje hned při INSERT (ne až při vystavení).
--   • Neměnný doklad — žádný updated_at (jako billing.invoice_party).
--   • Bez unique na invoice_id — k jedné faktuře může vzniknout víc PPD (dílčí
--     hotovostní úhrady); výběr řídí obsluha, aplikace to neomezuje.
--
-- Účastníci a rozpis DPH se na dokladu neukládají — odvozují se z faktury (jeden
-- zdroj počítá): strany ze snapshotů invoice_party, DPH z v_invoice_vat_summary.
-- Ukládá se jen přijatá částka (snapshot celkové částky faktury v okamžiku příjmu)
-- a účel platby.
--
-- Vlastní číselná řada `PPD{YYYYMM}###` (nezaměnitelná s fakturami `{YYYYMM}###`
-- a dobropisy `OD{YYYYMM}###`).
-- =============================================================================

SET search_path TO billing;

CREATE TABLE billing.cash_receipts (
    id             BIGSERIAL     PRIMARY KEY,
    receipt_number VARCHAR       NOT NULL,                 -- PPD{YYYYMM}###, přiděleno triggerem při INSERT
    invoice_id     BIGINT        NOT NULL,
    issue_date     DATE          NOT NULL DEFAULT CURRENT_DATE,
    amount         NUMERIC(12,2) NOT NULL,                 -- přijatá částka (snapshot celkové částky faktury)
    purpose        VARCHAR(255),                           -- účel platby (skládá aplikace: „Úhrada faktury č. …, VS …")
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by     BIGINT REFERENCES security.users(id) ON DELETE SET NULL,

    CONSTRAINT uq_cash_receipt_number UNIQUE (receipt_number),
    CONSTRAINT fk_cash_receipt_invoice
        FOREIGN KEY (invoice_id) REFERENCES billing.invoices(id),
    CONSTRAINT chk_cash_receipt_amount CHECK (amount >= 0)
);

CREATE INDEX idx_cash_receipts_invoice ON billing.cash_receipts (invoice_id);

-- =============================================================================
-- Trigger: číslo řady PPD{YYYYMM}### se přiděluje hned při INSERT.
-- Prefix z data vystavení (issue_date). Advisory lock per měsíc — souběžné
-- transakce čekají, místo aby soupeřily o pořadové číslo. Guard proti přetečení
-- řady (>999/měsíc). Vzor: fn_generate_invoice_number (V49), fn_generate_credit_note_number (V55).
-- =============================================================================
CREATE OR REPLACE FUNCTION billing.fn_generate_cash_receipt_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year_month VARCHAR(6);
    v_next_seq   INTEGER;
BEGIN
    IF NEW.receipt_number IS NOT NULL THEN
        RETURN NEW;
    END IF;

    v_year_month := TO_CHAR(COALESCE(NEW.issue_date, CURRENT_DATE), 'YYYYMM');
    PERFORM pg_advisory_xact_lock(hashtext('billing.cash_receipts.' || v_year_month));

    -- 'PPD' (3) + YYYYMM (6) = 9 znaků prefixu → pořadové číslo začíná na pozici 10.
    SELECT COALESCE(MAX(CAST(SUBSTRING(receipt_number FROM 10) AS INTEGER)), 0) + 1
    INTO v_next_seq
    FROM billing.cash_receipts
    WHERE receipt_number LIKE 'PPD' || v_year_month || '%';

    IF v_next_seq > 999 THEN
        RAISE EXCEPTION 'Cash receipt number sequence overflow for % (>999 in one month)', v_year_month;
    END IF;

    NEW.receipt_number := 'PPD' || v_year_month || LPAD(v_next_seq::TEXT, 3, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cash_receipts_generate_number
    BEFORE INSERT ON billing.cash_receipts
    FOR EACH ROW
    EXECUTE FUNCTION billing.fn_generate_cash_receipt_number();
