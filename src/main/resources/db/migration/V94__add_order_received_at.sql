-- V94: obchodní datum přijetí vozidla na zakázce
-- Zakázkový list tiskl auditní created_at, ale vůz mohl přijet jindy, než se
-- zakázka zapisuje. received_at zadává uživatel (FE předvyplní dneškem);
-- created_at zůstává čistě auditní údaj.

ALTER TABLE "order".orders
    ADD COLUMN received_at DATE;

UPDATE "order".orders
SET received_at = (created_at AT TIME ZONE 'Europe/Prague')::date;

ALTER TABLE "order".orders
    ALTER COLUMN received_at SET NOT NULL;

COMMENT ON COLUMN "order".orders.received_at IS
    'Datum přijetí vozidla do servisu (zadává uživatel, tiskne se na zakázkovém listu)';
