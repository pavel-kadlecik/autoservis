-- =============================================================================
-- V68__cash_receipt_cancellation.sql
-- Schéma: billing
--
-- Storno příjmového pokladního dokladu + jeden platný doklad na fakturu.
-- Audit 2026-07-30, nález KN-7 (rozhodnutí uživatele: „druhý PPD k téže faktuře zakázat").
--
-- Proč vůbec:
--   V57 nechala PPD bez unikátu na invoice_id („dílčí hotovostní úhrady, výběr řídí obsluha").
--   V praxi to znamenalo, že dvojklik na „Pokladní doklad" vystaví dva platné doklady na tutéž
--   částku k téže faktuře — pokladna pak vykazuje dvojnásobek přijaté hotovosti a čísla řady
--   PPD jsou spotřebovaná. Dílčí úhrady se nestaví (zůstávají jako TD-62), proto plný zákaz.
--
-- Proč storno a ne mazání:
--   Účetní doklad se nemaže (§35 ZoÚ — záznamy musí zůstat čitelné a doložitelné). Vystavený
--   omylem se ruší stornem: doklad zůstane v řadě, ale přestane platit. Teprve pak lze vystavit
--   nový — proto ČÁSTEČNÝ unikát (jen na nestornované), ne plný.
--
-- Proč vlastní ENUM a ne billing.invoice_status:
--   PPD nemá koncept ani stav „zaplaceno" (rozhodnutí z V57 — číslo dostane hned při INSERT).
--   Sdílený invoice_status by na dokladu připouštěl hodnoty DRAFT/PAID, které pro pokladní
--   doklad nedávají smysl a nikdo by je nehlídal.
--
-- Důvod storna je povinný (CHECK): stornovaný pokladní doklad bez vysvětlení je díra
-- v auditní stopě — účetní se za rok ptá „proč", ne „kdo".
-- =============================================================================

SET search_path TO billing;

CREATE TYPE billing.cash_receipt_status AS ENUM ('ISSUED', 'CANCELLED');

ALTER TABLE billing.cash_receipts
    ADD COLUMN status              billing.cash_receipt_status NOT NULL DEFAULT 'ISSUED',
    ADD COLUMN cancelled_at        TIMESTAMPTZ,
    ADD COLUMN cancelled_by        BIGINT REFERENCES security.users(id) ON DELETE SET NULL,
    ADD COLUMN cancellation_reason VARCHAR(255);

-- Stav a jeho doprovodné údaje musí sedět dohromady — polostornovaný doklad neexistuje.
ALTER TABLE billing.cash_receipts
    ADD CONSTRAINT chk_cash_receipt_cancellation CHECK (
        (status = 'ISSUED'
             AND cancelled_at IS NULL
             AND cancelled_by IS NULL
             AND cancellation_reason IS NULL)
        OR
        (status = 'CANCELLED'
             AND cancelled_at IS NOT NULL
             AND cancellation_reason IS NOT NULL)
    );

-- Jeden platný pokladní doklad na fakturu. Stornované se nepočítají, takže po stornu
-- lze vystavit nový. Vzor: uq_invoices_order_active (V48).
CREATE UNIQUE INDEX uq_cash_receipts_invoice_active
    ON billing.cash_receipts (invoice_id)
    WHERE status <> 'CANCELLED'::billing.cash_receipt_status;
