package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Řádek příjemky = šarže (warehouse.goods_receipt_items). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceiptItem {
    private Long id;
    private Long goodsReceiptId;
    private Long productId;
    private Integer position;
    private String nameSnapshot;
    private BigDecimal quantityReceived;
    private BigDecimal quantityRemaining;
    /**
     * Kolik z {@code quantityRemaining} je slíbeno otevřeným zakázkám a ještě nevydáno.
     *
     * <p><strong>Není sloupec tabulky</strong> — odvozuje se a plní ho jen
     * {@code findByIdsForUpdate}, tedy dotaz, který šarži před rezervací zamyká.
     * Ostatní dotazy ho nechávají {@code null}, což se čte jako nula.
     *
     * <p>Dostupné množství šarže = {@code quantityRemaining − quantityReserved}.
     */
    private BigDecimal quantityReserved;
    private BigDecimal unitPriceExclVat;
    private Integer vatRate;
    private BigDecimal totalInclVat;
    private OffsetDateTime createdAt;
}
