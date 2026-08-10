package cz.palo.autoservis.model.dto.warehouse;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTO pro skladové produkty (skladové karty).
 * <p>
 * Namespace pro vnořená response DTO:
 * <ul>
 * <li>{@link ListResponse} — řádek přehledu skladu</li>
 * <li>{@link DetailResponse} — plná skladová karta se šaržemi a historií pohybů</li>
 * <li>{@link BatchResponse} — jedna šarže příjemky s původem</li>
 * <li>{@link MovementResponse} — jeden skladový pohyb</li>
 * </ul>
 */
public final class ProductDto {

    private ProductDto() {
    }

    // =========================================================================
    // CreateRequest / UpdateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro založení skladové karty. {@code quantityOnHand} se tady nikdy
     * nenastavuje — je to cache udržovaná triggerem; zásoba přichází přes příjemky/pohyby.
     * {@code active} se řídí endpointy activate/deactivate.
     * Validace zrcadlí CHECK constrainty z V18/V21.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotBlank(message = "SKU (katalogové číslo) je povinné")
        @Size(max = 100, message = "SKU může mít maximálně 100 znaků")
        private String sku;

        @NotBlank(message = "Název je povinný")
        @Size(max = 500, message = "Název může mít maximálně 500 znaků")
        private String name;

        @Size(max = 255, message = "Výrobce může mít maximálně 255 znaků")
        private String manufacturer;

        @Size(max = 100, message = "Číslo dílu výrobce může mít maximálně 100 znaků")
        private String manufacturerPartNumber;

        @Size(max = 255, message = "Varianta může mít maximálně 255 znaků")
        private String variant;

        @Size(max = 500, message = "Poznámka může mít maximálně 500 znaků")
        private String note;

        @NotBlank(message = "Měrná jednotka je povinná")
        @Size(max = 20, message = "Měrná jednotka může mít maximálně 20 znaků")
        private String unit;

        @Min(value = 0, message = "Sazba DPH nemůže být záporná")
        @Max(value = 100, message = "Sazba DPH může být maximálně 100 %")
        private Integer defaultVatRate;

        @PositiveOrZero(message = "Prodejní cena nemůže být záporná")
        private BigDecimal salePrice;

        @PositiveOrZero(message = "Minimální stav nemůže být záporný")
        private BigDecimal minStockLevel;
    }

    /**
     * Vstupní DTO pro úpravu skladové karty. Stejný tvar jako {@link CreateRequest};
     * {@code active} a {@code quantityOnHand} tudy záměrně měnit nejdou.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        @NotBlank(message = "SKU (katalogové číslo) je povinné")
        @Size(max = 100, message = "SKU může mít maximálně 100 znaků")
        private String sku;

        @NotBlank(message = "Název je povinný")
        @Size(max = 500, message = "Název může mít maximálně 500 znaků")
        private String name;

        @Size(max = 255, message = "Výrobce může mít maximálně 255 znaků")
        private String manufacturer;

        @Size(max = 100, message = "Číslo dílu výrobce může mít maximálně 100 znaků")
        private String manufacturerPartNumber;

        @Size(max = 255, message = "Varianta může mít maximálně 255 znaků")
        private String variant;

        @Size(max = 500, message = "Poznámka může mít maximálně 500 znaků")
        private String note;

        @NotBlank(message = "Měrná jednotka je povinná")
        @Size(max = 20, message = "Měrná jednotka může mít maximálně 20 znaků")
        private String unit;

        @Min(value = 0, message = "Sazba DPH nemůže být záporná")
        @Max(value = 100, message = "Sazba DPH může být maximálně 100 %")
        private Integer defaultVatRate;

        @PositiveOrZero(message = "Prodejní cena nemůže být záporná")
        private BigDecimal salePrice;

        @PositiveOrZero(message = "Minimální stav nemůže být záporný")
        private BigDecimal minStockLevel;
    }

    /**
     * Řádek přehledu skladu. {@code quantityOnHand} je denormalizovaný aktuální
     * stav udržovaný DB triggerem nad skladovými pohyby.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ListResponse {

        private Long id;
        private String sku;
        private String name;
        private String manufacturer;
        private String manufacturerPartNumber;
        private String variant;
        private String unit;
        /** Fyzický stav — kolik kusů leží v regálu. Proti němu se dělá inventura. */
        private BigDecimal quantityOnHand;
        /** Kolik z fyzického stavu je slíbeno otevřeným zakázkám a ještě nevydáno. */
        private BigDecimal quantityReserved;
        /** {@code quantityOnHand − quantityReserved} — kolik lze ještě naplánovat. */
        private BigDecimal quantityAvailable;
        private BigDecimal salePrice;
        private BigDecimal minStockLevel;
        private Integer defaultVatRate;
        private Boolean active;
        /** True, když je díl hlídaný (má min. stav) a jeho <em>dostupné</em> množství kleslo pod tuto hranici. */
        private Boolean lowStock;
    }

    /**
     * Plná skladová karta: hlavička produktu plus jeho šarže a historie pohybů.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DetailResponse {

        private Long id;
        private String sku;
        private String name;
        private String manufacturer;
        private String manufacturerPartNumber;
        private String variant;
        private String note;
        private String unit;
        /** Fyzický stav — kolik kusů leží v regálu. Proti němu se dělá inventura. */
        private BigDecimal quantityOnHand;
        /** Kolik z fyzického stavu je slíbeno otevřeným zakázkám a ještě nevydáno. */
        private BigDecimal quantityReserved;
        /** {@code quantityOnHand − quantityReserved} — kolik lze ještě naplánovat. */
        private BigDecimal quantityAvailable;
        private BigDecimal salePrice;
        private BigDecimal minStockLevel;
        private Integer defaultVatRate;
        private Boolean active;
        /** True, když je díl hlídaný (má min. stav) a jeho <em>dostupné</em> množství kleslo pod tuto hranici. */
        private Boolean lowStock;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        private List<BatchResponse> batches;
        private List<MovementResponse> movements;
        /** Zakázky, které díl drží rezervovaný — proč není dostupný celý fyzický stav. */
        private List<ReservationResponse> reservations;
    }

    /**
     * Zakázka, která díl drží <strong>rezervovaný</strong> — naplánovala si ho, ale ze
     * skladu ještě neodešel (V83).
     *
     * <p>Odpovídá na otázku „proč je dostupné míň, než mám v regálu": obsluha vidí, kdo
     * si díl slíbil, a může se domluvit na přerovnání místo objednávání nového kusu.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReservationResponse {

        private Long orderId;
        private String orderNumber;
        private String customerName;
        private String orderStatus;
        private BigDecimal quantity;
        private OffsetDateTime reservedAt;
    }

    /**
     * Jedna šarže (řádek příjemky) s původem — odkazem na dodavatelskou
     * fakturu a objednávku.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BatchResponse {

        private Long batchId;
        private String nameSnapshot;
        private BigDecimal quantityReceived;
        private BigDecimal quantityRemaining;
        private BigDecimal unitPriceExclVat;
        private String invoiceNumber;
        private String orderNumber;
        private LocalDate issueDate;
        private String supplierName;
    }

    /**
     * Jeden skladový pohyb. {@code movementType} a {@code returnReason} se vracejí
     * jako surové řetězce enumů; na zobrazované popisky je mapuje frontend.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MovementResponse {

        private Long id;
        private String movementType;
        private BigDecimal quantity;
        private OffsetDateTime movedAt;
        private Long orderId;
        private String orderNumber;
        private String returnReason;
        private String creditNoteNumber;
        private String note;
    }
}
