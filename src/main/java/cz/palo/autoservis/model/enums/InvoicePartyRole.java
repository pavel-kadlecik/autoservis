package cz.palo.autoservis.model.enums;

/** Role strany na faktuře — mapuje se na PostgreSQL ENUM {@code billing.invoice_party_role}. */
public enum InvoicePartyRole {
    SUPPLIER,
    CUSTOMER
}
