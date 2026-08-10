package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.domain.warehouse.StockTakeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTO namespace pro inventuru (E6, P-5). */
public class StockTakeDto {

    /** Otevření inventury — soupis se nasnapshotuje serverem. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {
        @Size(max = 500, message = "Poznámka může mít nejvýše 500 znaků")
        private String note;
    }

    /** Řádek soupisu: co systém čeká, co bylo napočítáno a jaký je rozdíl. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemResponse {
        private Long id;
        private Long productId;
        private String sku;
        private String name;
        private String unit;
        /** Snapshot při otevření (informativní). */
        private BigDecimal expectedQuantity;
        /** Aktuální stav skladu — proti němu se počítá rozdíl při uzavření. */
        private BigDecimal currentQuantity;
        /** {@code null} = nepočítáno. */
        private BigDecimal countedQuantity;
        /** {@code counted − current}; {@code null} dokud není napočítáno. */
        private BigDecimal difference;
        private BigDecimal surplusUnitPrice;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DetailResponse {
        private Long id;
        /** Číslo dokladu INV-{rok}-{4 číslice} (V61). */
        private String stockTakeNumber;
        private StockTakeStatus status;
        private String note;
        private OffsetDateTime openedAt;
        private OffsetDateTime closedAt;
        private Long surplusReceiptId;
        /** Počet řádků s vyplněným množstvím. */
        private int countedLines;
        private int shortageLines;
        private int surplusLines;
        private List<ItemResponse> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ListResponse {
        private Long id;
        /** Číslo dokladu INV-{rok}-{4 číslice} (V61). */
        private String stockTakeNumber;
        private StockTakeStatus status;
        private String note;
        private OffsetDateTime openedAt;
        private OffsetDateTime closedAt;
    }

    /** Dávkový zápis soupisu — posílají se jen změněné řádky. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemsUpdateRequest {
        @NotNull(message = "Seznam položek je povinný")
        @Valid
        private List<ItemUpdate> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemUpdate {
        @NotNull(message = "ID položky je povinné")
        private Long id;
        /** {@code null} smaže napočítané množství (řádek se vrátí na „nepočítáno"). */
        @PositiveOrZero(message = "Napočítané množství nemůže být záporné")
        private BigDecimal countedQuantity;
        @PositiveOrZero(message = "Cena nemůže být záporná")
        private BigDecimal surplusUnitPrice;
    }

    /** Uzavření inventury — poznámka je volitelná. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloseRequest {
        @Size(max = 500, message = "Poznámka může mít nejvýše 500 znaků")
        private String note;
    }
}
