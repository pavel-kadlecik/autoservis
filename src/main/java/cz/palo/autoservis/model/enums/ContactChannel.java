package cz.palo.autoservis.model.enums;

/** Preferovaný komunikační kanál — mapuje se na PostgreSQL ENUM {@code customer.contact_channel}. */
public enum ContactChannel {
    EMAIL,
    PHONE,
    SMS,
    PORTAL
}
