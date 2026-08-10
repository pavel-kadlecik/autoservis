package cz.palo.autoservis.model.dto.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO pro profil firmy (identita vystavující firmy / dodavatele).
 */
public class CompanyProfileDto {

    /** Response DTO — aktuální identita firmy zobrazená v nastavení. */
    @Data
    public static class Response {
        private Integer id;
        private String  name;
        private String  ico;
        private String  dic;
        private String  street;
        private String  streetNumber;
        private String  city;
        private String  postalCode;
        private String  countryCode;
        private String  bankAccount;
        private String  iban;
        private String  swift;
        private Boolean invoiceNumberAuto;
        private String  invoiceNumberMask;
        /** Hlídat mezery v číselné řadě a varovat nad seznamem faktur (V89). */
        private Boolean invoiceGapCheckEnabled;
        /** Číslo, od kterého se hlídá; starší se ignorují. */
        private String  invoiceGapCheckFrom;
        /** Zdroj čísla PPD: MASK / INVOICE / MANUAL (V93). */
        private cz.palo.autoservis.model.enums.CashReceiptNumberSource cashReceiptNumberSource;
        private String  cashReceiptNumberMask;
        /** Hlídat mezery v číselné řadě pokladních dokladů (V92); v režimu INVOICE neaktivní. */
        private Boolean cashReceiptGapCheckEnabled;
        /** Číslo PPD, od kterého se hlídá; starší se ignorují. */
        private String  cashReceiptGapCheckFrom;
    }

    /** Request DTO pro úpravu profilu firmy. */
    @Data
    public static class UpdateRequest {

        @NotBlank(message = "Název firmy je povinný")
        @Size(max = 255, message = "Název firmy může mít maximálně 255 znaků")
        private String name;

        @Size(max = 15,  message = "IČO může mít maximálně 15 znaků")
        private String ico;

        @Size(max = 15,  message = "DIČ může mít maximálně 15 znaků")
        private String dic;

        @Size(max = 255, message = "Ulice může mít maximálně 255 znaků")
        private String street;

        @Size(max = 20,  message = "Číslo může mít maximálně 20 znaků")
        private String streetNumber;

        @Size(max = 100, message = "Město může mít maximálně 100 znaků")
        private String city;

        @Size(max = 10,  message = "PSČ může mít maximálně 10 znaků")
        private String postalCode;

        /**
         * Povinný: v DB je {@code country_code CHAR(2) NOT NULL} (V35). Bez validace tady se
         * chybějící kód propsal jako `null` a skončil surovým porušením integrity → 422 bez
         * vysvětlení, přestože je to vada vstupu (odhaleno testem rolové autorizace, vlna 6).
         */
        @NotBlank(message = "Kód země je povinný")
        @Size(min = 2, max = 2, message = "Kód země má právě 2 znaky")
        private String countryCode;

        @Size(max = 34,  message = "Číslo účtu může mít maximálně 34 znaků")
        private String bankAccount;

        @Size(max = 34,  message = "IBAN může mít maximálně 34 znaků")
        private String iban;

        @Size(max = 11,  message = "SWIFT/BIC může mít maximálně 11 znaků")
        private String swift;

        /**
         * Přepínač automatického číslování faktur (V71): zapnuto = číslo se skládá
         * podle masky a předvyplňuje v dialogu, vypnuto = volný ruční zápis.
         */
        @NotNull(message = "Přepínač automatického číslování je povinný")
        private Boolean invoiceNumberAuto;

        /**
         * Maska číselné řady. Povinná i při vypnutém přepínači (v DB je NOT NULL
         * a po pozdějším zapnutí musí být hned použitelná); obsahovou validaci
         * (tokeny, právě jedna sekvence) dělá service přes DocumentNumberMask.
         */
        @NotBlank(message = "Maska číselné řady je povinná")
        @Size(max = 40, message = "Maska může mít maximálně 40 znaků")
        private String invoiceNumberMask;
        private Boolean invoiceGapCheckEnabled;
        @Size(max = 20, message = "Číslo smí mít nejvýš 20 znaků")
        private String invoiceGapCheckFrom;

        /** Zdroj čísla PPD (V93): MASK = návrh dle masky, INVOICE = číslo faktury, MANUAL = ručně. */
        @NotNull(message = "Zdroj čísla pokladního dokladu je povinný")
        private cz.palo.autoservis.model.enums.CashReceiptNumberSource cashReceiptNumberSource;

        /**
         * Maska řady PPD. Povinná i při vypnutém přepínači (v DB NOT NULL); obsahovou
         * validaci dělá service přes DocumentNumberMask — stejně jako u faktur.
         */
        @NotBlank(message = "Maska číselné řady pokladních dokladů je povinná")
        @Size(max = 40, message = "Maska může mít maximálně 40 znaků")
        private String cashReceiptNumberMask;
        private Boolean cashReceiptGapCheckEnabled;
        @Size(max = 20, message = "Číslo smí mít nejvýš 20 znaků")
        private String cashReceiptGapCheckFrom;
    }
}
