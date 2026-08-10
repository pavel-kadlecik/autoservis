package cz.palo.autoservis.model.enums;

/** Typ záznamu v kalendáři – odpovídá typu ENUM {@code schedule.appointment_type} v PostgreSQL. */
public enum AppointmentType {
    /** Zákazník objednaný na termín. */
    BOOKING,
    /** Dílna zavřená — svátek, dovolená, revize. Bez zákazníka a vozidla. */
    CLOSURE,
    /**
     * Obecná událost — školení, revize, dovolená zaměstnance (V82). Bez zákazníka
     * a vozidla, volitelně s vazbou na zaměstnance. Na rozdíl od CLOSURE
     * <strong>neblokuje</strong> plánování objednávek.
     */
    EVENT
}
