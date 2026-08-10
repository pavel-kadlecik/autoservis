-- =============================================================================
-- V48__invoice_order_partial_unique.sql
-- Schéma: billing
--
-- Storno faktury (CANCELLED) už nesmí trvale zablokovat fakturaci zakázky
-- (audit K-1 / R-1). Plný UNIQUE(order_id) z V14 nahrazujeme částečným unikátním
-- indexem, který platí jen pro NEstornované faktury — k jedné zakázce tak smí být
-- nejvýš jedna aktivní faktura, ale libovolně mnoho stornovaných. Po stornu lze
-- vystavit fakturu novou (odpovídá záměru opravy V2, která po stornu odemyká
-- položky zakázky).
--
-- Navazuje změna v InvoiceMapper.findByOrderId (přidán filtr status <> CANCELLED),
-- aby vracel právě tu jednu aktivní fakturu.
-- =============================================================================

SET search_path TO billing;

ALTER TABLE billing.invoices DROP CONSTRAINT uq_invoices_order_id;

CREATE UNIQUE INDEX uq_invoices_order_active
    ON billing.invoices (order_id)
    WHERE status <> 'CANCELLED';
