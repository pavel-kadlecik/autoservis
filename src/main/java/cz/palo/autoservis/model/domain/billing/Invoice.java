package cz.palo.autoservis.model.domain.billing;

import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt hlavičky faktury — mapuje se na {@code billing.invoices}.
 *
 * <p>Faktura je daňový doklad navázaný na servisní zakázku ({@link Order}).
 * Zakázka má nejvýš jednu <em>aktivní</em> fakturu (dobropisovaná se uvolní,
 * viz {@code creditedAt}). Položky jsou uložené zvlášť
 * v {@code billing.invoice_items} a reprezentuje je {@link InvoiceItem}.
 *
 * <p>{@code invoiceNumber} je u konceptu {@code null} — skládá ho aplikace až při
 * vystavení (návrh podle masky číselné řady, nebo volný zápis), aby zrušený koncept
 * nespálil číslo řady. Po vystavení je neměnné — hlídá to DB trigger
 * {@code trg_invoices_number_immutable}. {@code variableSymbol} se negeneruje,
 * vyplňuje ho uživatel v témže dialogu (jen číslice, max. 10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    private Long           id;
    private String         invoiceNumber;
    private Long           orderId;
    private Long           customerId;
    private LocalDate      issueDate;
    private LocalDate      dueDate;
    private LocalDate      taxableSupplyDate;
    private String         variableSymbol;
    private String         constantSymbol;
    private String         specificSymbol;
    private PaymentMethod  paymentMethod;
    private InvoiceStatus  status;
    private String         note;

    /**
     * Číslo objednávky zákazníka — nákupní objednávka / PO (V91). Volný text bez
     * formátového omezení, {@code null} = neuvedeno. Záměrně ne „order number":
     * order je v projektu zakázka autoservisu ({@code orderNumberSnapshot} = ZAK-…).
     */
    private String         purchaseOrderNumber;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long           createdBy;
    private String         customerNameSnapshot;
    private String         orderNumberSnapshot;
    private String         vehicleLicensePlateSnapshot;
    private String         vehicleVinSnapshot;
    private String         vehicleBrandSnapshot;
    private String         vehicleModelSnapshot;
    private OffsetDateTime paidAt;
    private java.math.BigDecimal paidAmount;
    private PaymentMethod  paidMethod;

    /**
     * Kdy byl k faktuře vystaven opravný daňový doklad (dobropis); {@code null} = nedobropisovaná.
     *
     * <p>Faktura zůstává ve stavu ISSUED/PAID — je to pořád platný vystavený doklad. Přestává ale
     * být <em>aktivní</em> fakturou zakázky, takže zakázku lze fakturovat znovu
     * ({@code uq_invoices_order_active}, V69). Bez toho by chybná vystavená faktura zamkla zakázku
     * navždy: storno je od auditu KN-1 vyhrazené konceptu a dobropis stav faktury nemění.
     */
    private OffsetDateTime creditedAt;

    /**
     * Kdy obsluha potvrdila, že doklad dostal zákazník (V88). {@code null} = nepředáno,
     * a tehdy jde vystavenou fakturu ještě smazat — vystavení tenhle příznak nenastavuje.
     */
    private OffsetDateTime handedOverAt;

    /** Kdo předání potvrdil. */
    private Long handedOverBy;

    /**
     * Lidský popis dokladu do chybových hlášek — {@code "fakturu 202607001"}, nebo
     * {@code "koncept faktury"}, dokud číslo není přidělené.
     *
     * <p>Mezi V49 a V71 se {@code invoice_number} přiděloval až při vystavení, takže koncept
     * ho neměl (od V71 ho má každá faktura od založení; popis se pro jistotu drží null-safe).
     * Hláška složená prostým zřetězením proto obsluze u konceptu tvrdila „Zakázka už má fakturu
     * <strong>null</strong>" (audit 2026-07-30, regrese 02/F-7). Popis je tady, aby ho všechny
     * guardy nad fakturou skládaly jedním způsobem.
     *
     * <p><strong>Ve 4. pádě</strong> — patří do vazby „<em>má</em> …", kterou používají oba
     * volající (zámek položek zakázky a guard zrušení zakázky). U konceptu je 4. pád shodný
     * s 1. pádem („koncept faktury"), u vystaveného dokladu ne („fakturu", ne „faktura") —
     * první verze vracela 1. pád a hláška pak zněla „má faktura 202607007".
     *
     * <p>Není to getter (Lombok {@code @Data} generuje jen k polím) — jméno je záměrně mimo
     * konvenci {@code getX}, aby metoda nevypadala jako další vlastnost domény.
     *
     * @return popis dokladu pro uživatele ve 4. pádě; nikdy {@code null}
     */
    public String describe() {
        return invoiceNumber == null ? "koncept faktury" : "fakturu " + invoiceNumber;
    }
}
