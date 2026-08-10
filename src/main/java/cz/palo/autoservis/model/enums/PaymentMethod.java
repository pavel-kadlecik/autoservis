package cz.palo.autoservis.model.enums;

/** Způsob úhrady faktury — mapuje se na PostgreSQL ENUM {@code billing.payment_method}. */
public enum PaymentMethod {
    CARD,
    CASH,
    TRANSFER,
    CASH_OR_TRANSFER,
    CASH_OR_CARD,
    CARD_OR_TRANSFER
}


