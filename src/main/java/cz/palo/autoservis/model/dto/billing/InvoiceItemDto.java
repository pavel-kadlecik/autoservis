package cz.palo.autoservis.model.dto.billing;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * DTO pro entitu položky faktury.
 */
public class InvoiceItemDto {

    /** Response DTO vracené všemi čtecími operacemi. */
    @Data
    public static class Response {
        private Long id;
        private Long invoiceId;
        private Long orderItemId;
        private String name;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;
        private Short vatRate;
        private Short position;
        private BigDecimal net;    // základ (bez DPH) za řádek
        private BigDecimal vat;    // DPH za řádek
        private BigDecimal gross;  // s DPH za řádek
    }

    /** Request DTO pro přidání nové položky na fakturu. */
    @Data
    public static class CreateRequest {

        @NotNull(message = "Reference na položku zakázky je povinná")
        private Long orderItemId;

        @NotBlank(message = "Název položky je povinný")
        private String name;

        @NotNull(message = "Množství je povinné")
        @Positive(message = "Množství musí být větší než 0")
        private BigDecimal quantity;

        @NotBlank(message = "Jednotka je povinná")
        private String unit;

        @NotNull(message = "Jednotková cena je povinná")
        @PositiveOrZero(message = "Jednotková cena nesmí být záporná")
        private BigDecimal unitPrice;

        @NotNull(message = "Sazba DPH je povinná")
        @Min(value = 0, message = "Sazba DPH nesmí být záporná")
        @Max(value = 100, message = "Sazba DPH nesmí překročit 100")
        private Short vatRate;

        @PositiveOrZero(message = "Pozice nesmí být záporná")
        private Short position;
    }

    /** Request DTO pro úpravu existující položky faktury. */
    @Data
    public static class UpdateRequest {

        @NotBlank(message = "Název položky je povinný")
        private String name;

        @NotNull(message = "Množství je povinné")
        @Positive(message = "Množství musí být větší než 0")
        private BigDecimal quantity;

        @NotBlank(message = "Jednotka je povinná")
        private String unit;

        @NotNull(message = "Jednotková cena je povinná")
        @PositiveOrZero(message = "Jednotková cena nesmí být záporná")
        private BigDecimal unitPrice;

        @NotNull(message = "Sazba DPH je povinná")
        @Min(value = 0, message = "Sazba DPH nesmí být záporná")
        @Max(value = 100, message = "Sazba DPH nesmí překročit 100")
        private Short vatRate;

        @PositiveOrZero(message = "Pozice nesmí být záporná")
        private Short position;
    }
}
