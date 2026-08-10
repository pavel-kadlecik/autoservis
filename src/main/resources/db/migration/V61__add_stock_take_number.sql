-- =============================================================================
-- V61__add_stock_take_number.sql
-- Schéma: warehouse
--
-- Inventura dostává číslo dokladu INV-{rok}-{4 číslice}, číslované PER ROK
-- (reset každý rok) — stejným vzorem jako číslo zakázky (V56) a faktury (V49):
-- BEFORE INSERT trigger, per-rok MAX+1 s advisory lockem.
--
-- Existující inventury se dočíslují (backfill) podle pořadí opened_at v rámci
-- roku, aby na ně trigger u nových záznamů plynule navázal (MAX+1). Teprve po
-- backfillu se sloupec zamkne na NOT NULL + UNIQUE.
-- =============================================================================

SET search_path TO warehouse;

-- 1) Sloupec (zatím nullable kvůli backfillu existujících řádků)
ALTER TABLE warehouse.stock_takes ADD COLUMN stock_take_number VARCHAR(20);

-- 2) Číslovací funkce + trigger (per rok, advisory lock, MAX+1 — vzor V56)
CREATE OR REPLACE FUNCTION warehouse.fn_generate_stock_take_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year   TEXT;
    v_prefix TEXT;
    v_next   INTEGER;
BEGIN
    v_year   := EXTRACT(YEAR FROM NOW())::TEXT;
    v_prefix := 'INV-' || v_year || '-';

    -- Advisory lock per rok — souběžné INSERTy čekají, místo aby soupeřily o MAX.
    PERFORM pg_advisory_xact_lock(hashtext('warehouse.stock_takes.' || v_year));

    -- Pořadí = MAX existujícího čísla za daný rok + 1 (reset per rok, navazuje na backfill).
    SELECT COALESCE(MAX(CAST(SUBSTRING(stock_take_number FROM LENGTH(v_prefix) + 1) AS INTEGER)), 0) + 1
    INTO v_next
    FROM warehouse.stock_takes
    WHERE stock_take_number LIKE v_prefix || '%';

    -- LPAD guard: řada je 4místná; přetečení radši hlásit než tiše vyrobit 5místné číslo.
    IF v_next > 9999 THEN
        RAISE EXCEPTION 'Stock-take number sequence overflow for % (>9999 inventur in one year)', v_year;
    END IF;

    NEW.stock_take_number := v_prefix || LPAD(v_next::TEXT, 4, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_generate_stock_take_number
    BEFORE INSERT ON warehouse.stock_takes
    FOR EACH ROW
    WHEN (NEW.stock_take_number IS NULL OR NEW.stock_take_number = '')
    EXECUTE FUNCTION warehouse.fn_generate_stock_take_number();

-- 3) Backfill existujících inventur: per rok, pořadí podle opened_at (pak id).
WITH numbered AS (
    SELECT id,
           'INV-' || EXTRACT(YEAR FROM opened_at)::TEXT || '-'
           || LPAD(ROW_NUMBER() OVER (
                  PARTITION BY EXTRACT(YEAR FROM opened_at)
                  ORDER BY opened_at, id)::TEXT, 4, '0') AS num
    FROM warehouse.stock_takes
    WHERE stock_take_number IS NULL
)
UPDATE warehouse.stock_takes s
SET stock_take_number = n.num
FROM numbered n
WHERE s.id = n.id;

-- 4) Zamknout: číslo dokladu je povinné a jedinečné
ALTER TABLE warehouse.stock_takes ALTER COLUMN stock_take_number SET NOT NULL;
ALTER TABLE warehouse.stock_takes ADD CONSTRAINT uq_stock_takes_number UNIQUE (stock_take_number);

COMMENT ON COLUMN warehouse.stock_takes.stock_take_number IS
    'Číslo dokladu inventury INV-{rok}-{4 číslice}, resetované per rok; generuje trigger trg_generate_stock_take_number.';
