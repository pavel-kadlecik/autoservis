package cz.palo.autoservis.model.enums;

/** Typ položky zakázky — mapuje se na PostgreSQL ENUM {@code order.order_item_type}. */
public enum OrderItemType {
    LABOR,
    MATERIAL,
    OTHER_SERVICES
}
