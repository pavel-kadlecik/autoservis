-- =============================================================================
-- V82__add_calendar_event_type.sql
-- Třetí typ záznamu kalendáře: EVENT — obecná událost (přání zákazníka 2026-08-04).
--
-- Událost nezávisí na zákazníkovi ani vozidle (školení, revize, dovolená
-- zaměstnance). Od blokace dílny (CLOSURE) se liší tím, že NEBLOKUJE plánování
-- objednávek — dovolená jednoho mechanika dílnu nezavírá. Volitelná vazba na
-- zaměstnance dělá z dovolené evidovaný údaj, ne jen text v názvu.
--
-- Stejný vzor jako BOOKING/CLOSURE (V72): rozdíly mezi typy vynucují CHECK
-- constrainty, ne kód.
-- flyway:noAutoCommit
-- =============================================================================

-- Nová hodnota ENUMu nesmí být použita ve stejné transakci (vzor V17).
ALTER TYPE schedule.appointment_type ADD VALUE IF NOT EXISTS 'EVENT';

COMMIT;

-- =============================================================================
-- Vazba na zaměstnance (jen pro EVENT)
-- =============================================================================
-- RESTRICT: zaměstnanci se mažou soft-delete (is_active, D-4), tvrdé smazání
-- řádku s navěšenou dovolenou by tiše zahodilo evidovaný záznam.
ALTER TABLE schedule.appointments
    ADD COLUMN employee_id BIGINT;

ALTER TABLE schedule.appointments
    ADD CONSTRAINT fk_appointments_employee
        FOREIGN KEY (employee_id) REFERENCES employee.employees(id)
        ON DELETE RESTRICT;

COMMENT ON COLUMN schedule.appointments.employee_id IS
    'Jen pro EVENT (dovolená apod.). NULL = událost bez vazby na zaměstnance.';

-- =============================================================================
-- Pravidla, která hlídá databáze
-- =============================================================================

-- Událost nezávisí na zákazníkovi, vozidle ani zakázce (zrcadlo chk_appointments_closure_empty).
ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_event_empty
        CHECK (entry_type <> 'EVENT'
            OR (customer_id IS NULL AND vehicle_id IS NULL AND order_id IS NULL));

-- Událost musí mít konec — „dovolená navždy" nedává smysl (zrcadlo chk_appointments_closure_has_end).
ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_event_has_end
        CHECK (entry_type <> 'EVENT' OR ends_at IS NOT NULL);

-- Zaměstnanec patří jen k události — objednávka nese zákazníka, blokace nikoho.
ALTER TABLE schedule.appointments
    ADD CONSTRAINT chk_appointments_employee_event_only
        CHECK (employee_id IS NULL OR entry_type = 'EVENT');
