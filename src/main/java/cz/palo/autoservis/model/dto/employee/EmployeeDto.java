package cz.palo.autoservis.model.dto.employee;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * DTO pro entitu {@code Employee}.
 *
 * <p>Třída slouží jako namespace pro vnořená request/response DTO:
 * <ul>
 *   <li>{@link CreateRequest} — vstup pro založení nového zaměstnance</li>
 *   <li>{@link UpdateRequest} — vstup pro úpravu (bez {@code id} a auditních polí)</li>
 *   <li>{@link DetailResponse} — plná odpověď pro {@code GET /employees/{id}}</li>
 *   <li>{@link ListResponse} — zúžená odpověď pro seznam / select zaměstnanců</li>
 * </ul>
 *
 * <p>Validační omezení zrcadlí CHECK constrainty
 * z {@code V58__init_employee_schema.sql}. Zdrojem pravdy zůstává DB —
 * validace DTO jen dává rychlejší zpětnou vazbu před zápisem.
 */
public final class EmployeeDto {

    /** Privátní konstruktor — třída slouží jen jako namespace pro nested DTO. */
    private EmployeeDto() {
    }

    // =========================================================================
    // CreateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro založení nového zaměstnance.
     * Pole {@code id}, {@code isActive}, časová razítka a {@code createdBy}
     * se neposílají — řeší je DB defaulty, triggery a service vrstva.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        /** Volitelná vazba na přihlašovací účet (D-5). {@code null} = zaměstnanec bez loginu. */
        private Long userId;

        @NotBlank(message = "Jméno je povinné")
        @Size(max = 100, message = "Jméno může mít maximálně 100 znaků")
        private String firstName;

        @NotBlank(message = "Příjmení je povinné")
        @Size(max = 100, message = "Příjmení může mít maximálně 100 znaků")
        private String lastName;

        @Size(max = 100, message = "Pozice může mít maximálně 100 znaků")
        private String position;

        @PositiveOrZero(message = "Hodinová sazba nemůže být záporná")
        @Digits(integer = 8, fraction = 2, message = "Hodinová sazba smí mít nejvýše 8 míst a 2 desetinná")
        private BigDecimal hourlyRate;

        @NotNull(message = "Datum nástupu je povinné")
        private LocalDate hiredAt;

        /** Datum odchodu. {@code null} = stále zaměstnán. Pravidlo napříč poli ({@code >= hiredAt}) hlídá service. */
        private LocalDate leftAt;
    }

    // =========================================================================
    // UpdateRequest
    // =========================================================================

    /**
     * Vstupní DTO pro úpravu zaměstnance.
     * <p>
     * {@code isActive} je vynecháno — na aktivaci/deaktivaci jsou vyhrazené
     * endpointy (RESTful vzor, soft-delete). Změna {@link #hourlyRate} ovlivní
     * jen <em>aktuální</em> sazbu pro předvyplnění budoucích snapshotů;
     * historické položky si drží zmrazenou {@code purchase_price} (D-3).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {

        private Long userId;

        @NotBlank(message = "Jméno je povinné")
        @Size(max = 100, message = "Jméno může mít maximálně 100 znaků")
        private String firstName;

        @NotBlank(message = "Příjmení je povinné")
        @Size(max = 100, message = "Příjmení může mít maximálně 100 znaků")
        private String lastName;

        @Size(max = 100, message = "Pozice může mít maximálně 100 znaků")
        private String position;

        @PositiveOrZero(message = "Hodinová sazba nemůže být záporná")
        @Digits(integer = 8, fraction = 2, message = "Hodinová sazba smí mít nejvýše 8 míst a 2 desetinná")
        private BigDecimal hourlyRate;

        @NotNull(message = "Datum nástupu je povinné")
        private LocalDate hiredAt;

        private LocalDate leftAt;
    }

    // =========================================================================
    // DetailResponse
    // =========================================================================

    /** Plná odpověď s auditními poli — pro endpoint {@code GET /employees/{id}}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DetailResponse {

        private Long id;
        private Long userId;
        private String firstName;
        private String lastName;
        private String fullName;
        private String position;
        private BigDecimal hourlyRate;
        private LocalDate hiredAt;
        private LocalDate leftAt;
        private boolean active;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    // =========================================================================
    // ListResponse
    // =========================================================================

    /**
     * Zúžená odpověď pro seznam zaměstnanců a select u položky LABOR.
     * Nese {@code fullName} (hotové k vykreslení) a {@code hourlyRate}
     * (k předvyplnění nákupní ceny na frontendu, D-6).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ListResponse {

        private Long id;
        private String fullName;
        private String position;
        private BigDecimal hourlyRate;
        private LocalDate hiredAt;
        private LocalDate leftAt;
        private boolean active;
    }
}
