-- ============================================================================
-- V39__receipt_draft_workflow.sql
--
-- Workflow KONCEPTU příjemky (přepracování skladového importu, fáze 1).
--
-- Příjemka se už nematerializuje (produkty/šarže/pohyby) v okamžiku importu.
-- Import ukládá jen hlavičkový řádek + kanonický JSONB koncept
-- (řádky, stavy jistoty po polích, návrhy párování). Materializace
-- proběhne, až člověk příjemku POTVRDÍ; ODMÍTNUTÉ (REJECTED) koncepty
-- nematerializují nic a uvolní své číslo dokladu pro opakovaný import.
--
-- Změny:
--   1. Nové ENUMy warehouse.document_type, warehouse.receipt_source.
--   2. goods_receipts: nové sloupce (document_type, source_channel,
--      draft_payload, auditní sloupce confirmed_*/rejected_*).
--   3. Koncepty mohou být neúplné -> odstranění NOT NULL z hlavičkových polí,
--      která jsou zaručena až po potvrzení; úplnost řádků CONFIRMED nově
--      vynucuje CHECK constraint.
--   4. Idempotence: UNIQUE constraint uq_receipt_invoice se nahrazuje
--      částečným unikátním indexem, který ignoruje řádky REJECTED.
--   5. Jednorázový backfill vývojových dat: příjemky importované starou
--      pipeline (PENDING_REVIEW, ale šarže/pohyby už existují) se zpětně
--      označí CONFIRMED - opravdu byly naskladněny; ledger zůstává append-only.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. ENUM typy (nové typy použité okamžitě - bezpečné, na rozdíl od ALTER TYPE ADD VALUE)
-- ----------------------------------------------------------------------------

-- Druh zdrojového dokladu. Určuje, která pole doklad umí poskytnout:
-- dodací list („Není daňový doklad") nemá rekapitulaci DPH, splatnost ani DUZP -
-- DPH dopočítá kód z nakonfigurovaných výchozích hodnot při sestavení konceptu.
CREATE TYPE warehouse.document_type AS ENUM (
    'INVOICE',          -- daňový doklad (faktura)
    'DELIVERY_NOTE'     -- dodací list (není daňový doklad)
);

-- Vstupní kanál, který koncept vytvořil. Každý kanál produkuje stejný
-- kanonický payload konceptu; tohle jen zaznamenává původ.
CREATE TYPE warehouse.receipt_source AS ENUM (
    'AI_PDF',           -- AI extrakce z nahraného PDF
    'MANUAL',           -- ruční zadání přes formulář příjemky (prázdný koncept)
    'ISDOC'             -- rezervováno: adaptér českého e-fakturačního XML
);

-- ----------------------------------------------------------------------------
-- 2. Nové sloupce goods_receipts
-- ----------------------------------------------------------------------------

ALTER TABLE warehouse.goods_receipts
    ADD COLUMN document_type  warehouse.document_type  NOT NULL DEFAULT 'INVOICE',
    ADD COLUMN source_channel warehouse.receipt_source NOT NULL DEFAULT 'AI_PDF',
    ADD COLUMN draft_payload  JSONB,
    ADD COLUMN confirmed_at   TIMESTAMPTZ,
    ADD COLUMN confirmed_by   BIGINT,
    ADD COLUMN rejected_at    TIMESTAMPTZ,
    ADD COLUMN rejected_by    BIGINT,
    ADD COLUMN rejection_note VARCHAR(500);

ALTER TABLE warehouse.goods_receipts
    ADD CONSTRAINT fk_receipts_confirmed_by
        FOREIGN KEY (confirmed_by) REFERENCES security.users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_receipts_rejected_by
        FOREIGN KEY (rejected_by) REFERENCES security.users(id) ON DELETE SET NULL;

-- ----------------------------------------------------------------------------
-- 3. Koncepty mohou být neúplné
-- ----------------------------------------------------------------------------
-- Ve stavu PENDING_REVIEW je směrodatný draft_payload; hlavičkové sloupce jsou
-- jeho dotazovatelná projekce a do potvrzení mohou být NULL.

ALTER TABLE warehouse.goods_receipts
    ALTER COLUMN supplier_id            DROP NOT NULL,
    ALTER COLUMN supplier_name_snapshot DROP NOT NULL,
    ALTER COLUMN invoice_number         DROP NOT NULL,
    ALTER COLUMN subtotal               DROP NOT NULL,
    ALTER COLUMN vat_amount             DROP NOT NULL,
    ALTER COLUMN total_amount           DROP NOT NULL;

ALTER TABLE warehouse.goods_receipts
    DROP CONSTRAINT chk_receipt_totals;

ALTER TABLE warehouse.goods_receipts
    ADD CONSTRAINT chk_receipt_totals CHECK (
        (subtotal     IS NULL OR subtotal     >= 0) AND
        (vat_amount   IS NULL OR vat_amount   >= 0) AND
        (total_amount IS NULL OR total_amount >= 0)
    );

-- ----------------------------------------------------------------------------
-- 4. Idempotence: řádky REJECTED uvolní své číslo dokladu
-- ----------------------------------------------------------------------------
-- Dva koncepty bez dořešeného dodavatele nikdy nekolidují (NULL se porovnávají
-- jako různé); duplicity konceptů se znovu kontrolují v kódu při potvrzení.

ALTER TABLE warehouse.goods_receipts
    DROP CONSTRAINT uq_receipt_invoice;

CREATE UNIQUE INDEX uq_receipt_supplier_docno
    ON warehouse.goods_receipts (supplier_id, invoice_number)
    WHERE status <> 'REJECTED';

-- ----------------------------------------------------------------------------
-- 5. Jednorázový backfill vývojových dat (příjemky staré pipeline už byly naskladněny)
-- ----------------------------------------------------------------------------

UPDATE warehouse.goods_receipts gr
   SET status       = 'CONFIRMED',
       confirmed_at = NOW()
 WHERE gr.status = 'PENDING_REVIEW'
   AND EXISTS (SELECT 1
                 FROM warehouse.goods_receipt_items i
                WHERE i.goods_receipt_id = gr.id);

-- Příjemka CONFIRMED musí být úplná (přidáno záměrně až po backfillu;
-- backfillované řádky ho splňují - byly vloženy ještě za starých NOT NULL).
ALTER TABLE warehouse.goods_receipts
    ADD CONSTRAINT chk_receipt_confirmed_complete CHECK (
        status <> 'CONFIRMED' OR (
            supplier_id   IS NOT NULL AND
            invoice_number IS NOT NULL AND
            subtotal      IS NOT NULL AND
            vat_amount    IS NOT NULL AND
            total_amount  IS NOT NULL
        )
    );

-- ----------------------------------------------------------------------------
-- Komentáře
-- ----------------------------------------------------------------------------

COMMENT ON COLUMN warehouse.goods_receipts.invoice_number IS
    'Číslo dokladu: číslo faktury u INVOICE, číslo dodacího listu u DELIVERY_NOTE.';
COMMENT ON COLUMN warehouse.goods_receipts.document_type IS
    'Druh zdrojového dokladu, volí uživatel při nahrání (ne AI).';
COMMENT ON COLUMN warehouse.goods_receipts.source_channel IS
    'Vstupní kanál, který koncept vytvořil (AI_PDF / MANUAL / ISDOC).';
COMMENT ON COLUMN warehouse.goods_receipts.draft_payload IS
    'Kanonický koncept příjemky (řádky, stavy po polích, návrhy párování). Směrodatný ve stavu PENDING_REVIEW; po potvrzení/odmítnutí zmražený snapshot.';
COMMENT ON COLUMN warehouse.goods_receipts.rejection_note IS
    'Proč kontrolor koncept odmítl. Vyplněno jen u REJECTED.';
