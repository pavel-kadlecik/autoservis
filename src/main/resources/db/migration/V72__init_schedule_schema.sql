-- =============================================================================
-- V72__init_schedule_schema.sql
-- Schéma: schedule
--
-- Plánovací (objednávkový) kalendář — objednávka termínu vzniká DŘÍV než zakázka.
-- Zákazník volá v pondělí, přijede v úterý; zakázka do té doby neexistuje.
--
-- Proč samostatná tabulka a ne sloupce na "order".orders:
--   Část objednávek se na zakázku nikdy nepromění (zákazník nedorazí). Kdyby to byly
--   sloupce na zakázce, musely by se zakládat prázdné zakázky, které by zkreslily
--   fronty na dashboardu i statistiky.
--
-- Jedna tabulka nese dva druhy událostí (entry_type):
--   BOOKING — objednávka zákazníka (má zákazníka, vozidlo, případně zakázku)
--   CLOSURE — dílna zavřená (svátek, dovolená, revize zvedáku) — jen název a čas
--   Rozdíl hlídají CHECK constrainty, ne aplikační kód.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS schedule;

-- =============================================================================
-- ENUM typy
-- =============================================================================
CREATE TYPE schedule.appointment_type AS ENUM ('BOOKING', 'CLOSURE');

CREATE TYPE schedule.appointment_status AS ENUM (
    'PLANNED', 'CONFIRMED', 'CONVERTED', 'NO_SHOW', 'CANCELLED'
);

-- =============================================================================
-- Trigger funkce: automatická aktualizace updated_at při každém UPDATE
-- =============================================================================
CREATE OR REPLACE FUNCTION schedule.fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- Tabulka: appointments
-- =============================================================================
CREATE TABLE schedule.appointments (
    id            BIGSERIAL                    PRIMARY KEY,
    entry_type    schedule.appointment_type    NOT NULL,
    title         VARCHAR(200)                 NOT NULL,
    note          TEXT,
    starts_at     TIMESTAMPTZ                  NOT NULL,
    ends_at       TIMESTAMPTZ                  NOT NULL,
    customer_id   BIGINT,
    vehicle_id    BIGINT,
    order_id      BIGINT,
    status        schedule.appointment_status  NOT NULL DEFAULT 'PLANNED',
    is_active     BOOLEAN                      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ                  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ                  NOT NULL DEFAULT NOW(),
    created_by    BIGINT,

    -- ---------- cizí klíče ----------
    CONSTRAINT fk_appointments_customer
        FOREIGN KEY (customer_id) REFERENCES customer.customers(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_appointments_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle.vehicles(id)
        ON DELETE RESTRICT,

    -- Objednávka vznikla dřív než zakázka a přežije ji — odkaz je doplňkový údaj.
    CONSTRAINT fk_appointments_order
        FOREIGN KEY (order_id) REFERENCES "order".orders(id)
        ON DELETE SET NULL,

    CONSTRAINT fk_appointments_created_by
        FOREIGN KEY (created_by) REFERENCES security.users(id)
        ON DELETE SET NULL,

    -- ---------- pravidla, která hlídá databáze ----------

    CONSTRAINT chk_appointments_time_range
        CHECK (ends_at > starts_at),

    -- Objednávka (BOOKING) musí mít zákazníka.
    CONSTRAINT chk_appointments_booking_customer
        CHECK (entry_type <> 'BOOKING' OR customer_id IS NOT NULL),

    -- Zavřeno (CLOSURE) nesmí mít zákazníka ani vozidlo ani zakázku.
    CONSTRAINT chk_appointments_closure_empty
        CHECK (entry_type <> 'CLOSURE'
        OR (customer_id IS NULL AND vehicle_id IS NULL AND order_id IS NULL)),

    -- Převedená objednávka musí vědět, na kterou zakázku.
    CONSTRAINT chk_appointments_converted_order
        CHECK (status <> 'CONVERTED' OR order_id IS NOT NULL)
);

-- =============================================================================
-- Indexy
-- =============================================================================

-- Jedna zakázka smí vzniknout nejvýš z jedné objednávky.
-- Částečný index: NULL se v UNIQUE neporovnávají, takže objednávek bez zakázky může být kolik chce.
CREATE UNIQUE INDEX uq_appointments_order
    ON schedule.appointments (order_id)
    WHERE order_id IS NOT NULL;

-- Hlavní dotaz kalendáře: "co je v týdnu od–do".
CREATE INDEX idx_appointments_range
    ON schedule.appointments (starts_at, ends_at)
    WHERE is_active;

CREATE INDEX idx_appointments_customer ON schedule.appointments (customer_id);
CREATE INDEX idx_appointments_vehicle  ON schedule.appointments (vehicle_id);

-- =============================================================================
-- Trigger
-- =============================================================================
CREATE TRIGGER trg_appointments_updated_at
    BEFORE UPDATE ON schedule.appointments
    FOR EACH ROW
    EXECUTE FUNCTION schedule.fn_set_updated_at();

COMMENT ON TABLE schedule.appointments IS
    'Objednávky termínů (BOOKING) a blokace dílny (CLOSURE). Objednávka vzniká před zakázkou; '
    'po převodu ukazuje order_id na vzniklou zakázku a status je CONVERTED.';