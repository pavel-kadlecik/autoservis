package cz.palo.autoservis.model.domain.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Jeden řádek rekapitulace DPH faktury (základ / DPH / celkem po sazbách) —
 * mapuje se na view {@code billing.v_invoice_vat_summary}. Jen ke čtení,
 * odvozeno z položek faktury.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceVatSummary {

    private Long       invoiceId;
    private Short      vatRate;
    private BigDecimal base;
    private BigDecimal vat;
    private BigDecimal total;
}
