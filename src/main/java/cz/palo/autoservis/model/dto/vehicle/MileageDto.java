package cz.palo.autoservis.model.dto.vehicle;

import cz.palo.autoservis.model.enums.MileageSource;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * DTO pro odečty tachometru vozidla ({@code vehicle.mileage_history}).
 * <p>
 * Namespace pro vnořená request/response DTO:
 * <ul>
 * <li>{@link CreateRequest} — vstup pro zaznamenání nového odečtu</li>
 * <li>{@link UpdateRequest} — vstup pro opravu existujícího odečtu</li>
 * <li>{@link Response} — výstup s auditními poli</li>
 * </ul>
 * <p>
 * Validace zrcadlí CHECK constrainty z {@code V20__init_vehicle_mileage_history.sql};
 * autoritativním zdrojem pravdy zůstává DB.
 */
public final class MileageDto {

    /** Privátní konstruktor — třída slouží jen jako namespace pro nested DTO. */
    private MileageDto() {
    }

    // =========================================================================
    // CreateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro zaznamenání nového odečtu. {@code source} INITIAL service
     * odmítá — je vyhrazený pro počáteční odečet vzniklý při evidenci vozidla.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotNull(message = "Stav km je povinný")
        @PositiveOrZero(message = "Najeté kilometry nemohou být záporné")
        @Max(value = 9_999_999, message = "Najeté kilometry jsou mimo rozsah")
        private Integer mileageKm;

        @PastOrPresent(message = "Datum odečtu nesmí být v budoucnosti")
        private LocalDate recordedDate;

        @NotNull(message = "Zdroj čtení je povinný")
        private MileageSource source;

        @Size(max = 2000, message = "Poznámka může mít maximálně 2000 znaků")
        private String note;
    }

    // =========================================================================
    // UpdateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro opravu existujícího odečtu. Stejná editovatelná pole jako
     * {@link CreateRequest}; vozidlo, kterému odečet patří, změnit nelze.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        @NotNull(message = "Stav km je povinný")
        @PositiveOrZero(message = "Najeté kilometry nemohou být záporné")
        @Max(value = 9_999_999, message = "Najeté kilometry jsou mimo rozsah")
        private Integer mileageKm;

        @PastOrPresent(message = "Datum odečtu nesmí být v budoucnosti")
        private LocalDate recordedDate;

        @NotNull(message = "Zdroj čtení je povinný")
        private MileageSource source;

        @Size(max = 2000, message = "Poznámka může mít maximálně 2000 znaků")
        private String note;
    }

    // =========================================================================
    // Response
    // =========================================================================

    /**
     * Plná odpověď odečtu s auditními poli — pro endpointy
     * {@code /vehicles/{vehicleId}/mileage}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {

        private Long id;
        private Long vehicleId;
        private Integer mileageKm;
        private LocalDate recordedDate;
        private MileageSource source;
        private String note;
        private OffsetDateTime createdAt;
        private Long createdBy;
    }
}
