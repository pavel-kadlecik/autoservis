-- =============================================================================
-- V69__invoice_credited_unlocks_order.sql
-- Schéma: billing
--
-- Dobropisovaná faktura uvolní zakázku pro novou fakturu.
-- Audit 2026-07-30, díra objevená až po Vlně 2 (navazuje na KN-1 / KN-8).
--
-- Co se rozbilo:
--   Do Vlny 2 se chybná vystavená faktura řešila stornem — `uq_invoices_order_active`
--   (V48) stornované ignoruje, takže se zakázka uvolnila a šlo fakturovat znovu.
--   Vlna 2 storno vystaveného dokladu zamkla (§42/§45 ZDPH — oprava patří dobropisu),
--   jenže dobropis stav faktury nemění. Zakázka tím zůstala zamčená NAVŽDY: ekonomicky
--   ji dobropis vynuluje, ale doklad na správnou částku už k ní vystavit nešlo.
--
-- Řešení:
--   Nový sloupec `credited_at` — razítko okamžiku, kdy byl k faktuře VYSTAVEN dobropis
--   (koncept dobropisu nic neuvolňuje). Částečný unikát na zakázku dobropisované faktury
--   nepočítá, stejně jako nepočítá stornované. Faktura sama zůstává ve stavu ISSUED/PAID —
--   je to pořád platný vystavený doklad, jen už není tou „aktivní" fakturou zakázky.
--
-- Proč sloupec a ne stav `CREDITED` v `billing.invoice_status`:
--   Stav popisuje životní cyklus dokladu (koncept → vystaveno → zaplaceno), dobropisovanost
--   je na něm nezávislá — dobropisovat lze i zaplacenou fakturu a ta zaplacená zůstane.
--   Nová hodnota v ENUMu by navíc rozbila stavový automat, který Vlna 2 právě uzamkla.
--
-- Backfill: faktury s už vystaveným dobropisem se orazítkují časem vzniku toho dobropisu.
-- Bez něj by zakázky dobropisované před touto migrací zůstaly zamčené.
-- =============================================================================

SET search_path TO billing;

ALTER TABLE billing.invoices
    ADD COLUMN credited_at TIMESTAMPTZ;

COMMENT ON COLUMN billing.invoices.credited_at IS
    'Kdy byl k faktuře vystaven opravný daňový doklad (dobropis). NULL = nedobropisovaná. '
    'Dobropisovaná faktura přestává být aktivní fakturou zakázky (uq_invoices_order_active).';

UPDATE billing.invoices AS i
SET credited_at = cn.created_at
FROM billing.credit_notes AS cn
WHERE cn.original_invoice_id = i.id
  AND cn.status = 'ISSUED'::billing.invoice_status;

-- Aktivní faktura zakázky = nestornovaná A nedobropisovaná. Zbytek beze změny (V48).
DROP INDEX billing.uq_invoices_order_active;

CREATE UNIQUE INDEX uq_invoices_order_active
    ON billing.invoices (order_id)
    WHERE status <> 'CANCELLED'::billing.invoice_status
      AND credited_at IS NULL;
