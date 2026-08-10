package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Obsah QR platby (SPAYD). Audit (E6.8/S-3): PDF se ověřuje jen na hlavičku {@code %PDF}, takže
 * <strong>chybná částka v QR by prošla celou suitou</strong>. Tady se tvrdí přesný řetězec vč. částky.
 */
class SpaydBuilderTest {

    private static final String IBAN = "CZ6508000000192000145399";

    private final SpaydBuilder builder = new SpaydBuilder();

    private InvoiceDto.DetailResponse invoice(String number, String vs, BigDecimal gross, String iban) {
        InvoiceDto.PartyResponse supplier = new InvoiceDto.PartyResponse();
        supplier.setIban(iban);

        InvoiceDto.DetailResponse invoice = new InvoiceDto.DetailResponse();
        invoice.setInvoiceNumber(number);
        invoice.setVariableSymbol(vs);
        invoice.setTotalGross(gross);
        // Bez zaokrouhlení (nehotovostní úhrada) je částka k úhradě rovna daňovému součtu.
        invoice.setTotalToPay(gross);
        invoice.setStatus(cz.palo.autoservis.model.enums.InvoiceStatus.ISSUED);
        invoice.setSupplier(supplier);
        return invoice;
    }

    @Test
    @DisplayName("QR nese ZAOKROUHLENOU částku k úhradě, ne daňový součet (KN-7)")
    void build_cashInvoice_usesRoundedAmount() {
        var invoice = invoice("202607010", "202607010", new BigDecimal("6105.23"), IBAN);
        invoice.setRounding(new BigDecimal("-0.23"));
        invoice.setTotalToPay(new BigDecimal("6105.00"));

        // Kdyby QR neslo totalGross, žádalo by o jinou částku, než je na dokladu
        // v řádku „Celkem k úhradě".
        assertThat(builder.build(invoice)).contains("*AM:6105.00*");
    }

    @Test
    @DisplayName("chybějící totalToPay spadne na totalGross, ne na nulu")
    void build_missingTotalToPay_fallsBackToGross() {
        var invoice = invoice("202607011", "202607011", new BigDecimal("1210.00"), IBAN);
        invoice.setTotalToPay(null);

        assertThat(builder.build(invoice)).contains("*AM:1210.00*");
    }

    @Test
    @DisplayName("zaplacená ani stornovaná faktura QR platbu nedostane (KN-13)")
    void build_paidOrCancelledInvoice_hasNoQrPayment() {
        var paid = invoice("202607012", "202607012", new BigDecimal("1210.00"), IBAN);
        paid.setStatus(cz.palo.autoservis.model.enums.InvoiceStatus.PAID);
        // Kopie zaplaceného dokladu s funkčním QR na plnou částku vybízela k druhé úhradě.
        assertThat(builder.build(paid)).isNull();

        var cancelled = invoice("202607013", "202607013", new BigDecimal("1210.00"), IBAN);
        cancelled.setStatus(cz.palo.autoservis.model.enums.InvoiceStatus.CANCELLED);
        assertThat(builder.build(cancelled)).isNull();
    }

    @Test
    @DisplayName("plná faktura → přesný SPAYD včetně částky, CC:CZK a VS")
    void build_fullInvoice_exactString() {
        InvoiceDto.DetailResponse invoice =
                invoice("202607001", "202607001", new BigDecimal("1210.00"), "CZ6508000000192000145399");

        assertThat(builder.build(invoice))
                .isEqualTo("SPD*1.0*ACC:CZ6508000000192000145399*AM:1210.00*CC:CZK*X-VS:202607001*MSG:Faktura 202607001");
    }

    @Test
    @DisplayName("částka se formátuje na 2 desetinná místa (AM: přesně)")
    void build_amountScaledToTwoDecimals() {
        InvoiceDto.DetailResponse invoice =
                invoice("202607002", "202607002", new BigDecimal("6105.2"), "CZ6508000000192000145399");

        assertThat(builder.build(invoice)).contains("*AM:6105.20*");
    }

    @Test
    @DisplayName("koncept bez čísla → null (žádná QR)")
    void build_draftWithoutNumber_isNull() {
        assertThat(builder.build(invoice(null, null, new BigDecimal("100"), "CZ650800"))).isNull();
    }

    @Test
    @DisplayName("dodavatel bez IBAN → null (QR potřebuje účet)")
    void build_noIban_isNull() {
        assertThat(builder.build(invoice("202607003", "202607003", new BigDecimal("100"), null))).isNull();
    }

    @Test
    @DisplayName("prázdný variabilní symbol se do SPAYD nepřidá")
    void build_blankVariableSymbol_omitsVs() {
        InvoiceDto.DetailResponse invoice =
                invoice("202607004", "   ", new BigDecimal("100.00"), "CZ6508000000192000145399");

        assertThat(builder.build(invoice)).doesNotContain("X-VS");
    }

    @Test
    @DisplayName("MSG je bez diakritiky a bez oddělovače *")
    void sanitizeMessage_stripsDiacriticsAndDelimiter() {
        assertThat(builder.sanitizeMessage("Zálohová faktura * 2026"))
                .isEqualTo("Zalohova faktura   2026");
    }
}
