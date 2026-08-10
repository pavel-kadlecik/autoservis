package cz.palo.autoservis.model.domain.order;

import cz.palo.autoservis.model.enums.OrderItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Doménový objekt položky servisní zakázky — mapuje se na {@code order.order_items}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Položky patří zakázce a lze je volně přidávat, měnit i mazat
 * kdykoli během životního cyklu zakázky.
 *
 * <p>{@code purchasePrice} je interní nákladová cena viditelná jen pro vedení.
 * {@code unitPrice} je prodejní cena zobrazená zákazníkovi na faktuře.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    private Long id;
    private Long orderId;
    private Long goodsReceiptItemId;

    /**
     * Kolik z položky už fyzicky odešlo ze skladu — odvozené z ledgeru, neukládá se.
     * Nula u rezervace i u ručně zadané položky; plní jen dotazy, které to počítají.
     */
    private java.math.BigDecimal issuedQuantity;

    /** Katalogové číslo dílu (přes šarži); {@code null} u ručně zadané položky. */
    private String productSku;

    /** Příjemka, na které díl přišel — pro dohledání a reklamaci u dodavatele. */
    private Long goodsReceiptId;

    /** Dodavatel z příjemky (snapshot k okamžiku dokladu). */
    private String supplierName;

    /** Číslo dodavatelovy faktury, na které díl přišel. */
    private String receiptInvoiceNumber;
    private OrderItemType itemType;
    private String name;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal purchasePrice;
    private BigDecimal unitPrice;
    private Short vatRate;
    private Short position;
    private String note;

    /**
     * Mechanik, který práci provedl (D-1). Platné jen u položek {@code LABOR} —
     * DB CHECK ({@code chk_order_items_employee_labor}) ho u jiných typů zakazuje.
     * Při nastavení se <em>aktuální</em> {@code hourly_rate} zaměstnance snapshotuje
     * do {@link #purchasePrice} (D-3/D-6), takže pozdější změna sazby tuto položku
     * nikdy nepřepíše.
     */
    private Long employeeId;

    /**
     * Denormalizované celé jméno zaměstnance pro zobrazení — plní se JOINem
     * při čtení, neperzistuje se. {@code null}, když není přiřazen zaměstnanec.
     */
    private String employeeName;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long createdBy;
}
