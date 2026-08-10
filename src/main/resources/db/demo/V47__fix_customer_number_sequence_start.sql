-- =============================================================================
-- V47__fix_customer_number_sequence_start.sql
-- Schéma: customer
--
-- V4 založila customer_number_seq se START WITH 4, ale V3 seeduje deset zákazníků
-- (ZNK-2025-0001 .. ZNK-2025-0010). Čerstvá instalace provedená v roce seedu by
-- kolidovala u 4. vygenerovaného čísla zákazníka (audit N-11). Sekvence se posouvá
-- za seed. GREATEST(...) zaručuje, že se na instanci, kde už zákazníci vznikali,
-- nikdy neposune zpět.
--
-- Pozn.: ochrana proti přetečení LPAD (>9999 zákazníků/zakázek, >999 faktur
-- za měsíc, audit A9) se odkládá do příslušných přepisů triggerů (faktura =
-- plán E1.3, zakázka = plán E3.8); trigger čísla zákazníka V9 zatím nechává
-- LPAD(...,4).
-- =============================================================================

SET search_path TO customer;

SELECT setval('customer.customer_number_seq',
              GREATEST(10, (SELECT last_value FROM customer.customer_number_seq)));
