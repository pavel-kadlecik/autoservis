package cz.palo.autoservis.model.domain.billing;

import cz.palo.autoservis.model.enums.CashReceiptStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt příjmového pokladního dokladu (PPD) — mapuje se na {@code billing.cash_receipts}.
 *
 * <p>Potvrzení, že pokladna přijala hotovost k úhradě faktury ({@code invoiceId}). Náležitosti
 * účetního dokladu dle §11 zákona o účetnictví. Účastníci a rozpis DPH se neukládají — odvozují se
 * z faktury (viz {@code CashReceiptConverter}); ukládá se jen přijatá {@code amount} (snapshot
 * celkové částky faktury) a {@code purpose} (účel platby).
 *
 * <p>{@code receiptNumber} od V92 skládá <strong>aplikace</strong> podle masky z Fakturačních
 * údajů (zdroj čísla MASK / INVOICE / MANUAL, V93) a validuje ho pod zámkem řady — starý DB
 * generátor z V57 byl zrušen; po vystavení číslo hlídá guard trigger jako u faktur. Obsah dokladu
 * je neměnný (bez {@code updatedAt}); jediná povolená změna je <strong>storno</strong> dokladu
 * vystaveného omylem — {@code status}, {@code cancelledAt}, {@code cancelledBy},
 * {@code cancellationReason} (V68). Ke každé faktuře smí být nejvýš jeden nestornovaný doklad.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashReceipt {

    private Long              id;
    private String            receiptNumber;
    private Long              invoiceId;
    private LocalDate         issueDate;
    private BigDecimal        amount;
    private String            purpose;
    private CashReceiptStatus status;
    private OffsetDateTime    cancelledAt;
    private Long              cancelledBy;
    private String            cancellationReason;
    private OffsetDateTime    createdAt;
    private Long              createdBy;
}
