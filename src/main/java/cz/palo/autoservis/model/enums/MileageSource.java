package cz.palo.autoservis.model.enums;

/** Zdroj odečtu tachometru — mapuje se na PostgreSQL ENUM {@code vehicle.mileage_source}. */
public enum MileageSource {
    SERVICE,
    CUSTOMER,
    INITIAL,
    OTHER
}
