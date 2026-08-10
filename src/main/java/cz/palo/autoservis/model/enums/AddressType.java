package cz.palo.autoservis.model.enums;

/** Typ adresy zákazníka — mapuje se na PostgreSQL ENUM {@code customer.address_type}. */
public enum AddressType {
    BILLING,
    CONTACT,
    HEADQUARTERS
}
