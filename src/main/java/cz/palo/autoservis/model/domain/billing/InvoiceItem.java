package cz.palo.autoservis.model.domain.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Doménový objekt jedné položky faktury — mapuje se na {@code billing.invoice_items}.
 *
 * <p>Položka faktury je neměnný snapshot položky zakázky v okamžiku vystavení
 * faktury. Jednou vzniklé položky se nesmí měnit — faktura je daňový doklad
 * a dodatečné zásahy účetní předpisy zakazují.
 *
 * <p>{@code orderItemId} odkazuje na zdrojovou položku zakázky, ale všechna
 * ostatní pole ({@code name}, {@code quantity}, {@code unitPrice} atd.) jsou
 * nezávislé kopie, které se nezmění, ani když se položka zakázky později upraví.
 *
 * <p>Tabulka záměrně nemá sloupce {@code updated_at} ani {@code created_by}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceItem {

    private Long       id;
    private Long       invoiceId;
    private Long       orderItemId;
    private String     name;
    private BigDecimal quantity;
    private String     unit;
    private BigDecimal unitPrice;
    private Short      vatRate;
    private Short      position;

    // Odvozené (počítá SQL v findByInvoiceId, neukládá se): rozpad ceny řádku.
    private BigDecimal net;    // bez DPH
    private BigDecimal vat;    // DPH
    private BigDecimal gross;  // s DPH
}
