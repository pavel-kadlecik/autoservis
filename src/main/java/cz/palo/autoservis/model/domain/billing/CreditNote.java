package cz.palo.autoservis.model.domain.billing;

import cz.palo.autoservis.model.enums.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Doménový objekt opravného daňového dokladu (dobropisu) — mapuje se na {@code billing.credit_notes}.
 *
 * <p>Opravný daňový doklad dle §45 ZDPH. Váže se na původní fakturu ({@code originalInvoiceId} =
 * §45 evidenční číslo původního dokladu), nese {@code correctionReason} (§45 důvod opravy). Rozdílové
 * částky a identifikace stran se neukládají — odvozují se z původní faktury (viz {@code CreditNoteConverter}).
 *
 * <p>{@code creditNoteNumber} (řada „OD{YYYYMM}###") generuje DB trigger až při vystavení
 * (DRAFT→ISSUED); aplikace ho nenastavuje. Používají se jen stavy DRAFT a ISSUED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditNote {

    private Long           id;
    private String         creditNoteNumber;
    private Long           originalInvoiceId;
    private String         correctionReason;
    private LocalDate      issueDate;
    private LocalDate      taxableSupplyDate;
    private InvoiceStatus  status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long           createdBy;
}
