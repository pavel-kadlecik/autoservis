-- =============================================================================
-- V29__allow_issue_return_in_movement_sign_check.sql
-- Schéma: warehouse
-- Znovuvytvoření CHECK constraintu chk_movement_sign — povoluje ISSUE_RETURN s quantity > 0.
-- =============================================================================

ALTER TABLE warehouse.stock_movements
    DROP CONSTRAINT chk_movement_sign;

ALTER TABLE warehouse.stock_movements
    ADD CONSTRAINT chk_movement_sign CHECK (
        -- kladné pohyby (přírůstek na sklad)
        (movement_type = 'RECEIPT'    AND quantity > 0) OR
        (movement_type = 'ISSUE_RETURN' AND quantity > 0) OR
            -- korekce (cokoliv kromě nuly)
        (movement_type = 'ADJUSTMENT' AND quantity <> 0) OR
            -- záporné pohyby (úbytek ze skladu)
        (movement_type IN ('ISSUE','RETURN','WRITE_OFF') AND quantity < 0)

        );
