package cz.palo.autoservis.model.enums;

/** Typ převodovky vozidla — mapuje se na PostgreSQL ENUM {@code vehicle.transmission_type}. */
public enum TransmissionType {
    MANUAL,
    AUTOMATIC,
    SEMI_AUTOMATIC,
    CVT,
    DCT
}
