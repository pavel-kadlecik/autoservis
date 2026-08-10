-- =============================================================================
-- V51__invoice_payment_record.sql
-- Schéma: billing
--
-- Evidence úhrady faktury (audit K-9 / R-3, varianta a). Dosud byla „platba" jen
-- stav PAID — bez data, částky a skutečného způsobu úhrady. Doplňujeme tři pole,
-- která se plní při přechodu na PAID:
--   paid_at     — kdy byla faktura zaplacena (server NOW()),
--   paid_amount — jaká částka přišla (u plné úhrady = celková částka dokladu),
--   paid_method — jak reálně přišla (odlišené od předepsaného payment_method).
--
-- Sloupce jsou nullable (nezaplacená faktura je nemá). Částečné úhrady / více plateb
-- na fakturu (evidence 1:N) jsou dle rozhodnutí R-3 odloženy jako budoucí rozšíření.
-- =============================================================================

SET search_path TO billing;

ALTER TABLE billing.invoices
    ADD COLUMN paid_at     TIMESTAMPTZ,
    ADD COLUMN paid_amount NUMERIC(12,2),
    ADD COLUMN paid_method billing.payment_method;

COMMENT ON COLUMN billing.invoices.paid_at     IS 'Kdy byla faktura zaplacena (NULL = nezaplaceno). Audit K-9.';
COMMENT ON COLUMN billing.invoices.paid_amount IS 'Zaplacená částka (u plné úhrady = celková částka dokladu).';
COMMENT ON COLUMN billing.invoices.paid_method IS 'Skutečný způsob úhrady, odlišený od předepsaného payment_method.';

-- Backfill pro už zaplacené faktury — historická data nemáme, bereme nejlepší dostupné:
-- datum úhrady = datum vystavení, částka = spočtená celková částka dokladu, způsob = předepsaný.
UPDATE billing.invoices AS i
SET paid_at     = i.issue_date::timestamptz,
    paid_method = i.payment_method,
    paid_amount = COALESCE(
        (SELECT t.total_gross FROM billing.v_invoice_price_totals AS t WHERE t.invoice_id = i.id), 0)
WHERE i.status = 'PAID';
