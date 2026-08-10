-- =============================================================================
-- V7__add_vehicle_year_constraint.sql
-- Zajišťuje, že year_of_manufacture nepřekročí rok první registrace.
-- =============================================================================

ALTER TABLE vehicle.vehicles
    ADD CONSTRAINT chk_vehicles_year_registration
        CHECK (year_of_manufacture <= EXTRACT(YEAR FROM first_registration_date));
