package cz.palo.autoservis.model.domain.order;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt servisní zakázky — mapuje se na {@code order.orders}.
 *
 * <p>Čisté POJO bez JPA anotací a závislostí na Springu.
 * Databázové sloupce na pole mapuje MyBatis přes {@code ResultMap}
 * v {@code OrderMapper.xml}.
 *
 * <p>Zobrazovaná pole vozidla a zákazníka ({@code vehicleBrand},
 * {@code customerDisplayName} atd.) jsou denormalizované projekce načtené JOINem —
 * odrážejí stav v okamžiku dotazu a v tabulce zakázek se neukládají.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private Long id;
    private String orderNumber;

    private String customerDisplayName;
    private Long customerId;

    private Long vehicleId;
    private String vehicleModel;
    private String vehicleBrand;
    private String vehicleLicensePlate;
    private String vehicleVin;

    private OrderStatus status;
    private String description;
    private String internalNote;

    /**
     * Stav aktivní (nestornované) faktury této zakázky, nebo {@code null}, když zakázka
     * fakturu nemá (nebo má jen stornované). Odvozená projekce z {@code billing.invoices}
     * načtená JOINem v seznamovém dotazu — není sloupec {@code order.orders}, nezapisuje se.
     * Partial unique index {@code uq_invoices_order_active} zaručuje nejvýš jednu aktivní fakturu.
     */
    private InvoiceStatus invoiceStatus;

    /** ID aktivní faktury (týž predikát jako `invoiceStatus`); null, nemá-li zakázka fakturu. */
    private Long invoiceId;

    private OffsetDateTime estimatedCompletionAt;
    private OffsetDateTime completedAt;

    private BigDecimal estimatedPrice;
    private BigDecimal finalPrice;

    /**
     * Stav tachometru [km] při příjmu vozu — snímek pro zakázkový list (V70, audit KN-28).
     * {@code null} = nezadáno (vůz mohl přijet odtažený s nefunkčním tachometrem).
     *
     * <p>Odometr vozidla vede {@code vehicle.mileage_history}; tohle je údaj <em>dokladu</em>,
     * který zákazník podepsal, a proto se s pozdějšími odečty nemění. Při zakládání zakázky
     * se z něj zároveň zapíše odečet do historie vozidla (zdroj {@code SERVICE}).
     */
    private Integer mileageKmAtIntake;

    /**
     * Obchodní datum přijetí vozidla do servisu (V94) — tiskne se na zakázkovém listu.
     * Zadává uživatel (vůz mohl přijet jindy, než se zakázka zapisuje);
     * {@code createdAt} zůstává čistě auditní časová značka vzniku záznamu.
     */
    private LocalDate receivedAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long createdBy;
}
