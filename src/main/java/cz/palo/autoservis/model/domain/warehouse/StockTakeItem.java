package cz.palo.autoservis.model.domain.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Položka soupisu inventury (warehouse.stock_take_items, V44).
 *
 * <p>{@code countedQuantity == null} znamená <b>nepočítáno</b> — takový řádek
 * při uzavření negeneruje žádnou korekci (není to nula).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTakeItem {
    private Long id;
    private Long stockTakeId;
    private Long productId;
    /** Snapshot stavu při otevření — informativní; rozdíl se počítá proti aktuálnímu stavu. */
    private BigDecimal expectedQuantity;
    private BigDecimal countedQuantity;
    /** Nákupní cena pro případný přebytek (předvyplněná z nejnovější šarže). */
    private BigDecimal surplusUnitPrice;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
