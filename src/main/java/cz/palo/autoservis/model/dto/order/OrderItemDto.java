package cz.palo.autoservis.model.dto.order;

import cz.palo.autoservis.model.enums.OrderItemType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * DTO pro entitu položky servisní zakázky.
 */
public class OrderItemDto {

    /** Response DTO vracené všemi čtecími operacemi. */
    @Data
    public static class Response {
        private Long id;
        private Long orderId;
        private OrderItemType itemType;
        private String name;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal purchasePrice;
        private BigDecimal unitPrice;
        private Short vatRate;
        private Short position;
        private String note;
        private boolean fromStock;
        /**
         * Kolik z položky už odešlo ze skladu. Spolu s {@code fromStock} rozliší tři stavy,
         * které do 2026-08-07 vypadaly v tabulce položek naprosto stejně: ruční materiál
         * (sklad se ho netýká), rezervace (leží v regálu) a výdej (na autě).
         */
        private BigDecimal issuedQuantity;
        /** Katalogové číslo dílu; {@code null} u ručně zadané položky. */
        private String productSku;
        /**
         * Původ dílu — příjemka, dodavatel a číslo jeho faktury. Odpovídá na otázku
         * „u koho tenhle díl reklamovat"; {@code null} u ručně zadané položky.
         */
        private Long goodsReceiptId;
        private String supplierName;
        private String receiptInvoiceNumber;
        /** Přiřazený mechanik (jen LABOR, D-1); {@code null}, když žádný není. */
        private Long employeeId;
        /** Celé jméno mechanika pro zobrazení — snapshot z JOINu, neperzistuje se. */
        private String employeeName;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private Long createdBy;
    }

    /** Request DTO pro založení nové položky zakázky. */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Typ položky je povinný")
        private OrderItemType itemType;

        @NotBlank(message = "Název položky je povinný")
        private String name;

        @NotNull(message = "Množství je povinné")
        @Positive(message = "Množství musí být větší než 0")
        private BigDecimal quantity;

        @NotNull(message = "Jednotky jsou povinné")
        private String unit;

        @PositiveOrZero(message = "Nákupní cena nesmí být záporná")
        private BigDecimal purchasePrice;

        @NotNull(message = "Prodejní cena je povinná")
        @PositiveOrZero(message = "Prodejní cena nesmí být záporná")
        private BigDecimal unitPrice;

        @NotNull(message = "Sazba DPH je povinná")
        @Min(value = 0, message = "Sazba DPH nesmí být záporná")
        @Max(value = 100, message = "Sazba DPH nesmí překročit 100")
        private Short vatRate;

        @PositiveOrZero(message = "Pozice nesmí být záporná")
        private Short position;

        private String note;

        /**
         * Mechanik přiřazený k této práci (jen LABOR, D-1). Nepovinné. Když je vyplněn
         * a {@code purchasePrice} je prázdná, backend snapshotuje aktuální hodinovou
         * sazbu zaměstnance do {@code purchasePrice} (D-6).
         */
        private Long employeeId;
    }

    /** Request DTO pro úpravu existující položky zakázky. */
    @Data
    public static class UpdateRequest {

        @NotNull(message = "Typ položky je povinný")
        private OrderItemType itemType;

        @NotBlank(message = "Název položky je povinný")
        private String name;

        @NotNull(message = "Množství je povinné")
        @Positive(message = "Množství musí být větší než 0")
        private BigDecimal quantity;

        @NotNull(message = "Jednotky jsou povinné")
        private String unit;

        @PositiveOrZero(message = "Nákupní cena nesmí být záporná")
        private BigDecimal purchasePrice;

        @NotNull(message = "Prodejní cena je povinná")
        @PositiveOrZero(message = "Prodejní cena nesmí být záporná")
        private BigDecimal unitPrice;

        @NotNull(message = "Sazba DPH je povinná")
        @Min(value = 0, message = "Sazba DPH nesmí být záporná")
        @Max(value = 100, message = "Sazba DPH nesmí překročit 100")
        private Short vatRate;

        @PositiveOrZero(message = "Pozice nesmí být záporná")
        private Short position;

        private String note;

        /** Mechanik přiřazený k této práci (jen LABOR, D-1). Viz {@link CreateRequest#getEmployeeId()}. */
        private Long employeeId;
    }

    /** Request DTO pro hromadnou změnu pozic (přeuspořádání drag-and-drop). */
    @Data
    public static class ReorderRequest {
        private Long id;

        @PositiveOrZero(message = "Pozice nesmí být záporná")
        private Short position;
    }
}
