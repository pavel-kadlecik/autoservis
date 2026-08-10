package cz.palo.autoservis.model.enums;

/** Palivo / pohon vozidla — mapuje se na PostgreSQL ENUM {@code vehicle.fuel_type}. */
public enum FuelType {
    PETROL,
    DIESEL,
    LPG,
    CNG,
    ELECTRIC,
    HYBRID_PETROL,
    HYBRID_DIESEL,
    HYDROGEN,
    OTHER
}
