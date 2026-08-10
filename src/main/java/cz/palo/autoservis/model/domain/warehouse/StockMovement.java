package cz.palo.autoservis.model.domain.warehouse;

import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Skladový pohyb (warehouse.stock_movements). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovement {
    private Long id;
    private Long productId;
    private Long batchId;
    private MovementType movementType;
    private BigDecimal quantity;
    private Long orderId;
    /**
     * Položka zakázky, které se pohyb týká (V83). {@code null} u příjmů a ručních pohybů.
     *
     * <p>Podle její přítomnosti se pozná <strong>vydaná</strong> položka od pouze
     * <strong>rezervované</strong> — rezervace se z toho odvozuje a nikam se neukládá.
     * Bez cizího klíče schválně: ledger je append-only, takže by musel mít {@code ON DELETE},
     * a žádná varianta neprojde bez toho, aby něco rozbila (viz V83).
     */
    private Long orderItemId;
    private ReturnReason returnReason;
    private String creditNoteNumber;
    private String note;
    private OffsetDateTime movedAt;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
