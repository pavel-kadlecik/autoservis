package cz.palo.autoservis.model.dto.vehicle;

import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.FuelType;
import cz.palo.autoservis.model.enums.TransmissionType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * DTO pro entitu {@code Vehicle}.
 * <p>
 * Třída slouží jako namespace pro vnořená request/response DTO:
 * <ul>
 * <li>{@link CreateRequest} — vstup pro založení nového vozidla</li>
 * <li>{@link UpdateRequest} — vstup pro úpravu</li>
 * <li>{@link DetailResponse} — plná odpověď s auditními poli</li>
 * <li>{@link ListResponse} — zúžená odpověď pro seznamy</li>
 * </ul>
 * <p>
 * Validační omezení v {@code @Pattern}, {@code @Min/@Max} atd. zrcadlí CHECK
 * constrainty z migrace {@code V5__init_vehicle_schema.sql}. Autoritativním
 * zdrojem pravdy zůstává DB — validace DTO jen dává uživateli rychlejší
 * a srozumitelnější zpětnou vazbu před zápisem.
 */
public final class VehicleDto {

    /** Privátní konstruktor — třída slouží jen jako namespace pro nested DTO. */
    private VehicleDto() {
    }

    // =========================================================================
    // CreateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro založení nového vozidla.
     * Pole {@code id}, {@code isActive}, {@code createdAt}, {@code updatedAt}
     * a {@code createdBy} se neposílají — řeší je DB defaulty, triggery a service vrstva.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotNull(message = "ID zákazníka je povinné")
        private Long customerId;

        /**
         * Od V90 nepovinné — stroje (zahradní traktory, sekačky) VIN nemají.
         * Vzor záměrně připouští prázdný řetězec: FE posílá nevyplněná pole
         * jako {@code ""} a validace běží dřív než blankToNull normalizace
         * v konvertoru.
         */
        @Pattern(
                regexp = "^$|^[A-HJ-NPR-Z0-9]{17}$",
                message = "VIN musí mít přesně 17 znaků (A-Z bez I,O,Q a 0-9)"
        )
        private String vin;

        /** Výrobní číslo stroje bez VIN (V90). */
        @Size(max = 50, message = "Výrobní číslo může mít maximálně 50 znaků")
        private String machineSerialNumber;

        @Size(max = 15, message = "SPZ může mít maximálně 15 znaků")
        private String licensePlate;

        @NotBlank(message = "Značka je povinná")
        @Size(max = 100, message = "Značka může mít maximálně 100 znaků")
        private String brand;

        @NotBlank(message = "Model je povinný")
        @Size(max = 100, message = "Model může mít maximálně 100 znaků")
        private String model;

        @Min(value = 1885, message = "Rok výroby musí být alespoň 1885")
        @Max(value = 2100, message = "Rok výroby musí být rozumný (DB navíc kontroluje aktuální rok+1)")
        private Short yearOfManufacture;

        @PastOrPresent(message = "Datum první registrace nesmí být v budoucnosti")
        private LocalDate firstRegistrationDate;

        /** Od V86 nepovinné — přívěs nemá motor, takže nemá ani palivo. */
        private FuelType fuelType;

        private TransmissionType transmission;

        @Size(max = 30, message = "Kód motoru může mít maximálně 30 znaků")
        private String engineCode;

        @Min(value = 50, message = "Objem motoru musí být alespoň 50 ccm")
        @Max(value = 10000, message = "Objem motoru může být maximálně 10000 ccm")
        private Integer engineDisplacementCcm;

        @Min(value = 1, message = "Výkon motoru musí být alespoň 1 kW")
        @Max(value = 2000, message = "Výkon motoru může být maximálně 2000 kW")
        private Short enginePowerKw;

        @Size(max = 50, message = "Barva může mít maximálně 50 znaků")
        private String color;

        @PositiveOrZero(message = "Najeté kilometry nemohou být záporné")
        private Integer initialMileageKm;

        private String internalNote;
    }

    // =========================================================================
    // UpdateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro úpravu vozidla.
     * <p>
     * Pole {@code isActive} je vynechané — na aktivaci/deaktivaci existují
     * samostatné endpointy (RESTful vzor).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        @NotNull(message = "ID zákazníka je povinné")
        private Long customerId;

        /** Od V90 nepovinné — zdůvodnění prázdného řetězce viz {@link CreateRequest#vin}. */
        @Pattern(
                regexp = "^$|^[A-HJ-NPR-Z0-9]{17}$",
                message = "VIN musí mít přesně 17 znaků (A-Z bez I,O,Q a 0-9)"
        )
        private String vin;

        /** Výrobní číslo stroje bez VIN (V90). */
        @Size(max = 50, message = "Výrobní číslo může mít maximálně 50 znaků")
        private String machineSerialNumber;

        @Size(max = 15, message = "SPZ může mít maximálně 15 znaků")
        private String licensePlate;

        @NotBlank(message = "Značka je povinná")
        @Size(max = 100)
        private String brand;

        @NotBlank(message = "Model je povinný")
        @Size(max = 100)
        private String model;

        @Min(value = 1885, message = "Rok výroby musí být alespoň 1885")
        @Max(value = 2100, message = "Rok výroby musí být rozumný")
        private Short yearOfManufacture;

        @PastOrPresent(message = "Datum první registrace nesmí být v budoucnosti")
        private LocalDate firstRegistrationDate;

        /** Od V86 nepovinné — přívěs nemá motor, takže nemá ani palivo. */
        private FuelType fuelType;

        private TransmissionType transmission;

        @Size(max = 30, message = "Kód motoru může mít maximálně 30 znaků")
        private String engineCode;

        @Min(value = 50, message = "Objem motoru musí být alespoň 50 ccm")
        @Max(value = 10000, message = "Objem motoru může být maximálně 10000 ccm")
        private Integer engineDisplacementCcm;

        @Min(value = 1, message = "Výkon motoru musí být alespoň 1 kW")
        @Max(value = 2000, message = "Výkon motoru může být maximálně 2000 kW")
        private Short enginePowerKw;

        @Size(max = 50, message = "Barva může mít maximálně 50 znaků")
        private String color;

        private String internalNote;
    }

    // =========================================================================
    // DetailResponse
    // =========================================================================

    /**
     * Plná odpověď s auditními poli — pro endpoint {@code GET /vehicles/{id}}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DetailResponse {

        private Long id;
        private Long customerId;
        private CustomerDto.SummaryResponse customer;
        private String vin;
        private String machineSerialNumber;
        private String licensePlate;
        private String brand;
        private String model;
        private Short yearOfManufacture;
        private LocalDate firstRegistrationDate;
        private FuelType fuelType;
        private TransmissionType transmission;
        private String engineCode;
        private Integer engineDisplacementCcm;
        private Short enginePowerKw;
        private String color;
        private Integer currentMileageKm;
        /** Platnost STK do — denormalizováno z nejnovějšího snapshotu registru (jen ke čtení). */
        private LocalDate stkValidUntil;
        /** Kola (pneu/ráfky per náprava) z registru — jen ke čtení, plní sync trigger (V62). */
        private String wheels;
        private String internalNote;
        private boolean active;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    // =========================================================================
    // ListResponse
    // =========================================================================

    /**
     * Zúžená odpověď pro seznamy — pro endpointy
     * {@code GET /vehicles} a {@code GET /customers/{id}/vehicles}.
     * <p>
     * Vynechává technické detaily (převodovka, objem, výkon) a interní poznámku —
     * ty jsou v {@link DetailResponse}. Šetří přenos dat u velkých seznamů.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ListResponse {

        private Long id;
        private Long customerId;
        private CustomerDto.SummaryResponse customer;
        private String customerDisplayName;
        private String vin;
        private String machineSerialNumber;
        private String licensePlate;
        private String brand;
        private String model;
        private Short yearOfManufacture;
        private FuelType fuelType;
        private String color;
        private Integer currentMileageKm;
        /** Platnost STK do — denormalizováno z nejnovějšího snapshotu registru (jen ke čtení). */
        private LocalDate stkValidUntil;
        private boolean active;
        private OffsetDateTime createdAt;

    }

    /**
     * Record se souhrnnou odpovědí o vozidle — zjednodušený tvar
     * pro vkládání údajů o vozidle do jiných odpovědí.
     */
    public record SummaryResponse(
            Long id,
            String vin,
            String machineSerialNumber,
            String licensePlate,
            String brand,
            String model,
            Short yearOfManufacture,
            Integer currentMileageKm,
            boolean active
    ) {}


}