package cz.palo.autoservis.model.domain.billing;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Read model jednoho řádku seznamu faktur — není to entita tabulky.
 *
 * <p>Vzniká jedním JOIN dotazem, který kombinuje:
 * <ul>
 *   <li>{@code billing.invoices} — pole hlavičky faktury</li>
 *   <li>{@code "order".orders} — {@code orderNumber}</li>
 *   <li>{@code customer.customers} — {@code customerDisplayName}</li>
 *   <li>{@code billing.v_invoice_price_totals} — spočítané součty</li>
 * </ul>
 *
 * <p>Součty se joinují v jednom dotazu záměrně: načítání {@link InvoiceSummary}
 * na každý řádek zvlášť by způsobilo N+1 problém. U faktury bez položek dá
 * LEFT JOIN {@code NULL}, který SQL nahrazuje nulou přes {@code COALESCE}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceListRow {

    private Long          id;
    private String        invoiceNumber;
    private LocalDate     issueDate;
    private LocalDate     dueDate;
    private InvoiceStatus status;
    private PaymentMethod paymentMethod;
    private String        variableSymbol;

    /** Projekce z {@code "order".orders}. */
    private Long          orderId;
    private String        orderNumber;

    /** Projekce z {@code customer.customers}. */
    private Long          customerId;
    private String        customerDisplayName;

    /** Projekce z {@code billing.v_invoice_price_totals}. */
    private BigDecimal    totalNet;
    private BigDecimal    totalVat;
    private BigDecimal    totalGross;

    /** Částka k úhradě = {@code totalGross} + zaokrouhlení hotovosti (V67/KN-7). */
    private BigDecimal    totalToPay;

    /**
     * Kdy byl k faktuře <strong>vystaven</strong> dobropis (V69), nebo {@code null}.
     * Seznam podle toho ukazuje odznak „Dobropisována" — stav dokladu se dobropisem nemění
     * (zůstává ISSUED/PAID), takže bez tohohle pole nešlo v přehledu poznat, že je opravená.
     */
    private OffsetDateTime creditedAt;

    /** Kdy doklad dostal zákazník (V88); null u nepředané faktury. */
    private OffsetDateTime handedOverAt;

    /** Běží k faktuře rozpracovaná oprava (koncept dobropisu)? */
    private boolean hasDraftCreditNote;

    /** Popis zakázky — živě, ne snímek: slouží k orientaci v seznamu, ne jako část dokladu. */
    private String orderDescription;
}
