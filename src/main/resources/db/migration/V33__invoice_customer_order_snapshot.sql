-- =============================================================================
-- V33__invoice_customer_order_snapshot.sql
-- Schéma: billing
-- Přidává snapshot jména zákazníka a čísla zakázky na fakturu. Faktura je
-- dokument — tyto hodnoty musí zamrznout k datu vystavení a nesmí sledovat
-- pozdější změny zákazníka nebo zakázky.
-- Závisí na V14 (invoices), customer.customers, "order".orders.
-- =============================================================================

-- 1. přidej sloupce jako NULLABLE (projde i s existujícími řádky)
ALTER TABLE billing.invoices ADD COLUMN customer_name_snapshot VARCHAR(255);
ALTER TABLE billing.invoices ADD COLUMN order_number_snapshot  VARCHAR;

-- 2. dopočítej hodnoty ze staré struktury (JOIN na customers/orders).
--    U existujících faktur se použije AKTUÁLNÍ jméno zákazníka — historické
--    jméno k datu vystavení není nikde uloženo (proto se snapshot zavádí).
UPDATE billing.invoices i SET
    customer_name_snapshot = CASE
                                 WHEN c.customer_type = 'COMPANY' THEN c.company_name
                                 ELSE c.first_name || ' ' || c.last_name
                             END,
    order_number_snapshot  = o.order_number
FROM customer.customers AS c, "order".orders AS o
WHERE i.order_id = o.id AND i.customer_id = c.id;

-- 3. teď, když žádný řádek není NULL, přidej NOT NULL
ALTER TABLE billing.invoices ALTER COLUMN customer_name_snapshot SET NOT NULL;
ALTER TABLE billing.invoices ALTER COLUMN order_number_snapshot  SET NOT NULL;

COMMENT ON COLUMN billing.invoices.customer_name_snapshot IS
    'Zobrazované jméno zákazníka zmražené k datu vystavení — faktura je neměnný dokument a nesmí sledovat pozdější změny zákazníka.';
COMMENT ON COLUMN billing.invoices.order_number_snapshot IS
    'Číslo zakázky zmražené k datu vystavení — drží se na faktuře, aby přežilo změny navázané zakázky.';
