package cz.palo.autoservis.model.dto.warehouse;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Řádek příjemky = šarže (warehouse.goods_receipt_items). Jen ke čtení: šarže
 *  vznikají importem a quantity_remaining udržuje trigger ze stock_movements. */
public class GoodsReceiptItemDto {

    /** Jeden importovaný řádek příjemky. */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Response {
        private Long id;
        private Long productId;
        private String nameSnapshot;
        private BigDecimal quantityReceived;
        private BigDecimal quantityRemaining;
        /**
         * Kolik z toho, co v šarži zbývá, drží otevřené zakázky (odvozené, neukládá se).
         * Naplní jen dotazy, které to počítají — jinde zůstane {@code null}.
         */
        private BigDecimal quantityReserved;
        /**
         * Kolik lze ze šarže ještě slíbit = {@code quantityRemaining − quantityReserved}.
         * {@code null}, když se rezervace nepočítaly — „nevím" se nesmí tvářit jako „všechno".
         */
        private BigDecimal quantityAvailable;
        private BigDecimal unitPriceExclVat;
        private Integer vatRate;
    }

    /** Požadavek uživatele na import šarže z příjemky. */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ImportRequest {

        @NotNull
        @Positive
        private Long goodsReceiptItemId;

        @NotNull
        @Positive
        private BigDecimal quantity;
    }




}