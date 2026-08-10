package cz.palo.autoservis.model.dto.billing;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTOs pro odeslání faktury e-mailem zákazníkovi.
 *
 * <p>Návrh e-mailu ({@link DraftResponse}) skládá server — kostru textu z dat dokladu
 * (číslo, částka, splatnost) a podpis názvem firmy z Fakturačních údajů. Obsluha ji v dialogu
 * libovolně upraví a finální znění se vrací v {@link SendRequest}; server odesílá
 * <strong>přesně to, co obsluha potvrdila</strong>, nic nedoplňuje.
 */
public class InvoiceEmailDto {

    /** Návrh e-mailu ({@code GET /invoices/{id}/email-draft}) — předvyplnění dialogu. */
    @Data
    public static class DraftResponse {
        /** E-mail zákazníka z jeho karty; {@code null} = zákazník e-mail nemá, obsluha ho zadá. */
        private String recipient;
        private String subject;
        private String body;
    }

    /** Finální znění e-mailu k odeslání ({@code POST /invoices/{id}/send-email}). */
    @Data
    public static class SendRequest {

        @NotBlank(message = "E-mail příjemce je povinný")
        @Email(message = "E-mail příjemce nemá platný formát")
        @Size(max = 255, message = "E-mail příjemce může mít maximálně 255 znaků")
        private String recipient;

        @NotBlank(message = "Předmět je povinný")
        @Size(max = 200, message = "Předmět může mít maximálně 200 znaků")
        private String subject;

        @NotBlank(message = "Text e-mailu je povinný")
        @Size(max = 5000, message = "Text e-mailu může mít maximálně 5000 znaků")
        private String body;
    }
}
