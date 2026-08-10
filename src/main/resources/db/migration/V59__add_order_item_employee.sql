-- =============================================================================
-- V59__add_order_item_employee.sql
-- Vazba mechanika na POLOŽKU zakázky typu LABOR (D-1). Na jednom autě může
-- dělat víc mechaniků, každý svoje hodiny — proto vazba na položku, ne na
-- zakázku. Sazba se při přiřazení snímkuje do existujícího order_items.purchase_price
-- (D-3), tento sloupec drží jen identitu, kdo práci odvedl.
-- Závisí na V12 (order_items) a V58 (employee.employees).
-- =============================================================================

ALTER TABLE "order".order_items
    ADD COLUMN employee_id BIGINT,

    ADD CONSTRAINT fk_order_items_employee
        FOREIGN KEY (employee_id) REFERENCES employee.employees(id)
        ON UPDATE CASCADE ON DELETE RESTRICT,

    -- D-2: mechanika lze přiřadit jen k práci (LABOR) — garance na DB, ne jen v aplikaci
    ADD CONSTRAINT chk_order_items_employee_labor
        CHECK (employee_id IS NULL OR item_type = 'LABOR');

CREATE INDEX idx_order_items_employee ON "order".order_items (employee_id);
