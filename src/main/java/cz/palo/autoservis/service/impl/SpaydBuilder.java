package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;

/**
 * Sestavení řetězce „QR Platba" (SPAYD — Short Payment Descriptor, český standard).
 * Vyčleněno z tiskové služby, aby šel obsah přesně otestovat (audit E6.8/S-3): chybná částka
 * v QR by jinak prošla celou suitou — PDF se jen ověřuje na hlavičku `%PDF`, ne na obsah platby.
 */
@Component
public class SpaydBuilder {

    /**
     * Vrátí SPAYD řetězec, nebo {@code null} když platbu nelze nebo nemá smysl sestavit
     * (koncept bez čísla, už zaplacená či stornovaná faktura, dodavatel bez IBAN).
     *
     * <p>Příklad: {@code SPD*1.0*ACC:CZ..*AM:6105.00*CC:CZK*X-VS:..*MSG:..}
     */
    public String build(InvoiceDto.DetailResponse invoice) {
        // Koncept (DRAFT) je jen návrh dokladu („NÁVRH — NENÍ DAŇOVÝ DOKLAD") — QR platba
        // pro něj nedává smysl. Do V71 to jistila absence čísla; číslo má teď koncept
        // od založení, takže se rozhoduje výhradně podle stavu.
        if (invoice.getStatus() == cz.palo.autoservis.model.enums.InvoiceStatus.DRAFT
                || invoice.getInvoiceNumber() == null) {
            return null;
        }
        // Zaplacený ani stornovaný doklad se platit nemá (audit KN-13). Kopie faktury
        // s funkčním QR kódem na plnou částku přímo vybízela k druhé úhradě.
        if (invoice.getStatus() == cz.palo.autoservis.model.enums.InvoiceStatus.PAID
                || invoice.getStatus() == cz.palo.autoservis.model.enums.InvoiceStatus.CANCELLED) {
            return null;
        }
        InvoiceDto.PartyResponse supplier = invoice.getSupplier();
        if (supplier == null || supplier.getIban() == null || supplier.getIban().isBlank()) {
            return null;
        }

        String account = supplier.getIban().replaceAll("\\s", "");
        // BIC se do QR záměrně nedává: SPAYD čtou jen české/slovenské banky a u tuzemské
        // CZK platby BIC není potřeba (je to institut zahraničních plateb).

        // Částka k úhradě, ne daňový součet: u hotovostní faktury je zaokrouhlená na celé Kč
        // (V67/KN-7). Kdyby QR neslo `totalGross`, žádalo by o jinou částku, než je na dokladu
        // v řádku „Celkem k úhradě" — a to je horší než původní neshoda.
        //
        // Fallback na `totalGross`, ne na nulu: chybějící `totalToPay` znamená „zaokrouhlení
        // neznáme", ne „platí se nic". QR s nulovou částkou by byl tiše nefunkční doklad.
        BigDecimal payable = invoice.getTotalToPay() != null
                ? invoice.getTotalToPay()
                : invoice.getTotalGross();
        String amount = payable != null
                ? payable.setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "0.00";

        StringBuilder spayd = new StringBuilder("SPD*1.0");
        spayd.append("*ACC:").append(account);
        spayd.append("*AM:").append(amount);
        spayd.append("*CC:CZK");
        if (invoice.getVariableSymbol() != null && !invoice.getVariableSymbol().isBlank()) {
            spayd.append("*X-VS:").append(invoice.getVariableSymbol());
        }
        spayd.append("*MSG:").append(sanitizeMessage("Faktura " + invoice.getInvoiceNumber()));
        return spayd.toString();
    }

    /** Zbaví text diakritiky a oddělovače SPAYD a ořízne na 60 znaků. */
    String sanitizeMessage(String message) {
        String ascii = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("*", " ")
                .trim();
        return ascii.length() > 60 ? ascii.substring(0, 60) : ascii;
    }
}
