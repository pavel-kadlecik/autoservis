package cz.palo.autoservis.model.dto.order;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * DTO pro entitu servisní zakázky.
 */
public class OrderDto {

    /** Response DTO pro stránkované seznamové endpointy. */
    @Data
    public static class ListResponse {
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
        /** Stav aktivní faktury zakázky (null = nefakturováno). Odvozeno v seznamovém dotazu. */
        private InvoiceStatus invoiceStatus;
        /** ID aktivní faktury; null = nefakturováno. */
        private Long invoiceId;
        private String description;
        private String internalNote;
        private OffsetDateTime estimatedCompletionAt;
        private OffsetDateTime completedAt;
        private BigDecimal estimatedPrice;
        private BigDecimal finalPrice;
        private Integer mileageKmAtIntake;
        private LocalDate receivedAt;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private Long createdBy;
    }

    /** Response DTO pro detailové endpointy. */
    @Data
    public static class DetailResponse {
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
        /**
         * Stav aktivní faktury (null = nefakturováno) a její ID. Detail je nese kvůli dialogu
         * zrušení: podle stavu nabídne storno konceptu, nebo proklik na vystavení dobropisu.
         */
        private InvoiceStatus invoiceStatus;
        private Long invoiceId;
        private String description;
        private String internalNote;
        private OffsetDateTime estimatedCompletionAt;
        private OffsetDateTime completedAt;
        private BigDecimal estimatedPrice;
        private BigDecimal finalPrice;
        private Integer mileageKmAtIntake;
        private LocalDate receivedAt;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private Long createdBy;
    }

    /** Request DTO pro založení nové servisní zakázky. */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Zákazník je povinný")
        private Long customerId;

        @NotNull(message = "Vozidlo je povinné")
        private Long vehicleId;

        @NotBlank(message = "Popis zakázky je povinný")
        @Size(max = 2000, message = "Popis může mít maximálně 2000 znaků")
        private String description;

        @Size(max = 2000, message = "Interní poznámka může mít maximálně 2000 znaků")
        private String internalNote;

        @FutureOrPresent(message = "Termín dokončení nesmí být v minulosti")
        private OffsetDateTime estimatedCompletionAt;

        @PositiveOrZero
        private BigDecimal estimatedPrice;

        /**
         * Stav tachometru [km] při příjmu vozu — nepovinný údaj zakázkového listu (V70).
         * Rozsah zrcadlí DB CHECK {@code chk_orders_mileage_at_intake} i historii tachometru.
         * Je-li vyplněn, založí se z něj zároveň odečet v historii vozidla (zdroj SERVICE).
         */
        @PositiveOrZero(message = "Stav tachometru nesmí být záporný")
        @Max(value = 9_999_999, message = "Stav tachometru je mimo rozsah")
        private Integer mileageKmAtIntake;

        /**
         * Datum přijetí vozidla (V94) — tiskne se na zakázkovém listu. FE předvyplní dneškem,
         * uživatel může přepsat libovolně (i do budoucna — rozhodnutí uživatele 2026-08-09:
         * žádné omezení, zodpovědnost nese obsluha).
         */
        @NotNull(message = "Datum přijetí je povinné")
        private LocalDate receivedAt;
    }

    /**
     * Změna samotného stavu zakázky — vyhrazená cesta místo full-replace {@code PUT}.
     *
     * <p>Obdoba {@code AppointmentDto.StatusRequest}: nese jedinou hodnotu, takže rychlá
     * změna stavu ze seznamu nepřepíše popis ani ceny hodnotami, které si klient nenačetl.
     */
    @Data
    public static class StatusRequest {

        @NotNull(message = "Stav zakázky je povinný")
        private OrderStatus status;
    }

    /**
     * Request DTO pro úpravu existující servisní zakázky.
     * {@code customerId} a {@code vehicleId} jsou záměrně vynechány —
     * po založení zakázky je změnit nelze.
     */
    @Data
    public static class UpdateRequest {

        @NotNull(message = "Stav zakázky je povinný")
        private OrderStatus status;

        @NotBlank(message = "Popis zakázky je povinný")
        @Size(max = 2000, message = "Popis může mít maximálně 2000 znaků")
        private String description;

        @Size(max = 2000, message = "Interní poznámka může mít maximálně 2000 znaků")
        private String internalNote;

        private OffsetDateTime estimatedCompletionAt;

        @PositiveOrZero
        private BigDecimal estimatedPrice;

        @PositiveOrZero
        private BigDecimal finalPrice;

        private OffsetDateTime completedAt;

        /**
         * Stav tachometru při příjmu — v editaci lze dopsat, když ho obsluha při zakládání
         * nevyplnila. Odečet v historii vozidla se z editace <strong>nezakládá</strong>:
         * dodatečně zjištěný km je údaj dokladu, odometr vozu se plní na jeho vlastní kartě
         * (jinak by opakovaná editace zakázky sypala do historie duplicitní odečty).
         */
        @PositiveOrZero(message = "Stav tachometru nesmí být záporný")
        @Max(value = 9_999_999, message = "Stav tachometru je mimo rozsah")
        private Integer mileageKmAtIntake;

        /** Datum přijetí vozidla — editovatelné (list se tiskne ze živých dat, oprava se propíše). */
        @NotNull(message = "Datum přijetí je povinné")
        private LocalDate receivedAt;
    }
}
