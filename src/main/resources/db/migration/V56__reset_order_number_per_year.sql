-- =============================================================================
-- V56__reset_order_number_per_year.sql
-- TD-57: číslo zakázky ZAK-{rok}-{4 číslice} se má resetovat KAŽDÝ ROK.
--
-- Původní V11 skládalo číslo z GLOBÁLNÍ sekvence order_number_seq, takže v novém
-- roce pokračovalo dál (nezačalo od 0001) — číselná řada se neresetovala.
-- Přepis na vzor faktury (V49): per-rok MAX+1 s advisory lockem. Řada se tak
-- resetuje přirozeně (MAX je scopnutý na rok) a navazuje i na seed bez kolize
-- (nová zakázka = nejvyšší existující číslo daného roku + 1).
--
-- Trigger trg_generate_order_number (BEFORE INSERT, WHEN order_number IS NULL/'')
-- zůstává beze změny — mění se jen tělo funkce.
-- =============================================================================

CREATE OR REPLACE FUNCTION "order".fn_generate_order_number()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_year   TEXT;
    v_prefix TEXT;
    v_next   INTEGER;
BEGIN
    v_year   := EXTRACT(YEAR FROM NOW())::TEXT;
    v_prefix := 'ZAK-' || v_year || '-';

    -- Advisory lock per rok — souběžné INSERTy čekají, místo aby soupeřily o MAX.
    PERFORM pg_advisory_xact_lock(hashtext('order.orders.' || v_year));

    -- Pořadí = MAX existujícího čísla za daný rok + 1 (reset per rok, navazuje na seed).
    SELECT COALESCE(MAX(CAST(SUBSTRING(order_number FROM LENGTH(v_prefix) + 1) AS INTEGER)), 0) + 1
    INTO v_next
    FROM "order".orders
    WHERE order_number LIKE v_prefix || '%';

    -- LPAD guard (E0.9): řada je 4místná; přetečení radši hlásit než tiše vyrobit 5místné číslo.
    IF v_next > 9999 THEN
        RAISE EXCEPTION 'Order number sequence overflow for % (>9999 orders in one year)', v_year;
    END IF;

    NEW.order_number := v_prefix || LPAD(v_next::TEXT, 4, '0');
    RETURN NEW;
END;
$$;

-- Globální sekvence už není potřeba — per-rok MAX+1 ji nahradil.
DROP SEQUENCE IF EXISTS "order".order_number_seq;
