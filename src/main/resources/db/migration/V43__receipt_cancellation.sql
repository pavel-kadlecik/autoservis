-- =============================================================================
-- V43__receipt_cancellation.sql
-- Schéma: warehouse
-- Storno potvrzené příjemky (rozhodnutí R-C, docs/analyza-sklad-2026-07.md):
-- nový stav CANCELLED + auditní sloupce. Skladová data se NEMAŽOU — storno
-- zapíše kompenzační pohyby (SAP vzor 101/102), ledger zůstává append-only.
-- Závisí na V18 (goods_receipts) a V39 (receipt_status, částečný unikátní index).
--
-- flyway:noAutoCommit
-- PostgreSQL neumí přidat hodnotu do ENUMu a použít ji ve stejné transakci
-- (nový index ji používá v predikátu) — proto explicitní COMMIT, vzor V17.
-- =============================================================================

ALTER TYPE warehouse.receipt_status ADD VALUE IF NOT EXISTS 'CANCELLED';

COMMIT;

-- -----------------------------------------------------------------------------
-- 1. Auditní stopa storna (kdo, kdy, proč)
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse.goods_receipts
    ADD COLUMN cancelled_at      TIMESTAMPTZ,
    ADD COLUMN cancelled_by      BIGINT,
    ADD COLUMN cancellation_note VARCHAR(500);

ALTER TABLE warehouse.goods_receipts
    ADD CONSTRAINT fk_receipts_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES security.users (id) ON DELETE SET NULL;

COMMENT ON COLUMN warehouse.goods_receipts.cancelled_at IS
    'Kdy byla potvrzená příjemka stornována (kompenzační pohyby).';
COMMENT ON COLUMN warehouse.goods_receipts.cancellation_note IS
    'Důvod storna - povinný při stornu, jako u zamítnutí.';

-- -----------------------------------------------------------------------------
-- 2. Stornovaný doklad uvolní své číslo pro re-import
-- -----------------------------------------------------------------------------
-- Stejná sémantika jako u REJECTED (V39): zamítnutý ani stornovaný doklad
-- nesmí blokovat opravný import téhož čísla od téhož dodavatele.
DROP INDEX IF EXISTS warehouse.uq_receipt_supplier_docno;

CREATE UNIQUE INDEX uq_receipt_supplier_docno
    ON warehouse.goods_receipts (supplier_id, invoice_number)
    WHERE status NOT IN ('REJECTED', 'CANCELLED');
