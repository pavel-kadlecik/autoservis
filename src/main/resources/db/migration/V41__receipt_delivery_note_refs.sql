-- ============================================================================
-- V41__receipt_delivery_note_refs.sql
--
-- Deduplikace DL <-> faktura (přepracování skladového importu, fáze 7).
--
-- Vzor LKQ: zboží nejdřív dorazí s DODACÍM LISTEM (naskladní se jako příjemka)
-- a pozdější souhrnná FAKTURA opakuje stejné položky pod skupinovým řádkem
-- „Dodací list č. X celkem ...". Import obou by zboží naskladnil dvakrát.
--
-- Tato tabulka zaznamenává, na které dodací listy se faktura odkazuje, zda byl
-- DL spárován s existující příjemkou a jak to kontrolor vyřešil:
--   LINKED    = jen provázat, řádky DL znovu NEnaskladňovat,
--   RESTOCKED = naskladnit normálně (kontrolor tvrdí, že zboží opravdu dorazilo znovu).
-- ============================================================================

CREATE TYPE warehouse.dn_ref_resolution AS ENUM (
    'LINKED',       -- jen provázat, znovu nenaskladňovat
    'RESTOCKED'     -- naskladnit i podruhé (vědomé rozhodnutí kontrolora)
);

CREATE TABLE warehouse.receipt_delivery_note_refs (
    id                   BIGSERIAL   PRIMARY KEY,
    goods_receipt_id     BIGINT      NOT NULL,
    delivery_note_number VARCHAR(50) NOT NULL,
    matched_receipt_id   BIGINT,
    resolution           warehouse.dn_ref_resolution,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_dn_refs_receipt
        FOREIGN KEY (goods_receipt_id) REFERENCES warehouse.goods_receipts(id) ON DELETE CASCADE,
    CONSTRAINT fk_dn_refs_matched
        FOREIGN KEY (matched_receipt_id) REFERENCES warehouse.goods_receipts(id) ON DELETE SET NULL,
    CONSTRAINT uq_dn_ref UNIQUE (goods_receipt_id, delivery_note_number)
);

CREATE INDEX idx_dn_refs_matched ON warehouse.receipt_delivery_note_refs (matched_receipt_id);

COMMENT ON TABLE warehouse.receipt_delivery_note_refs IS
    'Dodací listy, na které se faktura odkazuje (skupinové řádky LKQ). matched_receipt_id + resolution řídí pojistku proti dvojímu naskladnění při potvrzení.';
