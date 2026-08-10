package cz.palo.autoservis.model.domain.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Doménový objekt spočítaných součtů faktury —
 * mapuje se na databázové view {@code billing.v_invoice_price_totals}.
 *
 * <p>Hodnoty se počítají z {@code billing.invoice_items} po řádcích
 * (zaokrouhleno na 2 desetinná místa) a pak sčítají, takže
 * {@code totalGross == totalNet + totalVat} platí vždy na halíř.
 *
 * <p>Faktura bez položek ve view žádný řádek nemá — v tom případě použij
 * {@link #zero(Long)} místo vracení {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSummary {

    private Long       invoiceId;
    private BigDecimal totalNet;
    private BigDecimal totalVat;
    private BigDecimal totalGross;

    /**
     * Rozdíl ze zaokrouhlení celkové částky na celou korunu u hotovostní úhrady
     * (V67, audit KN-7). Nula u nehotovostní úhrady.
     *
     * <p><strong>Stojí mimo základ daně</strong> (§36 odst. 5 ZDPH), takže
     * {@link #totalNet} ani {@link #totalVat} neovlivňuje — proto neplatí
     * {@code totalToPay == totalNet + totalVat}, ale
     * {@code totalToPay == totalGross + rounding}.
     */
    private BigDecimal rounding;

    /**
     * Částka, kterou zákazník skutečně platí = {@link #totalGross} + {@link #rounding}.
     *
     * <p>Jediný zdroj pro PDF („Celkem k úhradě"), QR platbu, pokladní doklad
     * i evidenci úhrady — dřív si každý z nich zaokrouhloval po svém a čísla
     * se rozcházela.
     */
    private BigDecimal totalToPay;

    /**
     * Vrací nulový souhrn pro fakturu bez položek.
     *
     * @param invoiceId ID faktury
     * @return souhrn se všemi částkami {@link BigDecimal#ZERO}
     */
    public static InvoiceSummary zero(Long invoiceId) {
        BigDecimal z = BigDecimal.ZERO;
        return InvoiceSummary.builder()
                .invoiceId(invoiceId)
                .totalNet(z)
                .totalVat(z)
                .totalGross(z)
                .rounding(z)
                .totalToPay(z)
                .build();
    }
}
