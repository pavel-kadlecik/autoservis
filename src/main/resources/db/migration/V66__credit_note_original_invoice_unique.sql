-- =============================================================================
-- V66__credit_note_original_invoice_unique.sql
-- Schéma: billing
--
-- Jeden aktivní opravný daňový doklad na fakturu (audit 2026-07-30, nález KN-8).
--
-- `credit_notes` neměla na `original_invoice_id` žádný unikát a
-- `CreditNoteServiceImpl.createFromInvoice` existenci dřívějšího dobropisu neověřoval.
-- Každý dobropis přitom nese CELOU zápornou fakturu (MVP = plný dobropis, R-7), takže
-- dva dobropisy k téže faktuře znamenají dvojnásobné snížení daně na výstupu a zápornou
-- pohledávku. Dosud to šlo jen přímým voláním API — jakmile ale dobropis dostane
-- frontend (plán 2.1), byla by duplicita na dvě kliknutí.
--
-- Částečný index (vzor `uq_invoices_order_active`, V48): stornovaný doklad místo
-- neblokuje, takže po případném stornu půjde vystavit nový. Storno dobropisu dnes
-- endpoint nemá — index je připravený na dobu, kdy vznikne, a do té doby se chová
-- jako plný unikát.
--
-- Poznámka k rozsahu: tohle je hranice „jeden aktivní doklad". Až přibude ČÁSTEČNÝ
-- dobropis (TD-62), pravidlo se změní na „součet dobropisů ≤ faktura" a index bude
-- potřeba nahradit — proto je vědomě samostatný, ne součást tabulky.
-- =============================================================================

SET search_path TO billing;

CREATE UNIQUE INDEX uq_credit_notes_original_active
    ON billing.credit_notes (original_invoice_id)
    WHERE status <> 'CANCELLED';

COMMENT ON INDEX billing.uq_credit_notes_original_active IS
    'Jeden aktivní opravný daňový doklad na fakturu (KN-8). Stornovaný neblokuje — po stornu lze vystavit nový.';
