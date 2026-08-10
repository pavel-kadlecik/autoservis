package cz.palo.autoservis.model.domain.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Inventura — hlavička (warehouse.stock_takes, V44). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTake {
    private Long id;
    /** Číslo dokladu INV-{rok}-{4 číslice}, generuje DB trigger (V61). */
    private String stockTakeNumber;
    private StockTakeStatus status;
    private String note;
    private OffsetDateTime openedAt;
    private Long openedBy;
    private OffsetDateTime closedAt;
    private Long closedBy;
    /** Pseudo-příjemka typu STOCK_TAKE s inventurními přebytky (vzniká při uzavření). */
    private Long surplusReceiptId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
