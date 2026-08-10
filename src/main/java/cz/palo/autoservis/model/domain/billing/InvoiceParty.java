package cz.palo.autoservis.model.domain.billing;

import cz.palo.autoservis.model.enums.InvoicePartyRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Doménový objekt zmrazené strany faktury (dodavatel nebo odběratel) —
 * mapuje se na {@code billing.invoice_party}.
 *
 * <p>Neměnný snapshot právní identity strany v okamžiku vystavení faktury.
 * Faktura je daňový doklad, takže tyto hodnoty se nesmí změnit, ani když se
 * podkladový záznam zákazníka či firmy později upraví.
 *
 * <p>Tabulka záměrně nemá sloupec {@code updated_at}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceParty {

    private Long             id;
    private Long             invoiceId;
    private InvoicePartyRole role;

    private String           name;
    private String           ico;
    private String           dic;

    private String           street;
    private String           streetNumber;
    private String           city;
    private String           postalCode;
    private String           countryCode;

    private String           bankAccount;
    private String           iban;
    private String           swift;

    private OffsetDateTime   createdAt;
}
