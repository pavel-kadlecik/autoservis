package cz.palo.autoservis.model.dto.billing;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Parametry hledání pro seznamový endpoint faktur.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 *
 * <p>Zděděno ze {@code SearchParams}: {@code search} (fulltext — porovnává se
 * s číslem faktury, jménem zákazníka, VIN a SPZ vozidla).
 * Zděděno z {@code BaseParams}: {@code page}, {@code pageSize}, {@code offset}.
 */
@Getter
@Setter
public class InvoiceSearchParams extends SearchParams {

    /**
     * Vychozi razeni seznamu - nejnovejsi faktury prvni.
     *
     * Default patri sem, ne do <otherwise> v XML: tam by jako jediny
     * ignoroval sortDesc a nebyl by z Javy videt (U3R.1).
     */
    public InvoiceSearchParams() {
        setSortBy("issueDate");
        setSortDesc(true);
    }


    /** Když je vyplněno, vrací se jen faktury v tomto stavu. */
    private InvoiceStatus status;

    /** Když je vyplněno, vrací se jen faktury vystavené v tento den nebo později. */
    private LocalDate issueDateFrom;

    /** Když je vyplněno, vrací se jen faktury vystavené v tento den nebo dříve. */
    private LocalDate issueDateTo;

    /**
     * Když {@code true}, vrací se jen faktury po splatnosti — vystavené (ISSUED)
     * s prošlým datem splatnosti (E2.2 / audit K-9). DRAFT (ještě není doklad),
     * PAID a CANCELLED jsou vyloučeny.
     */
    private Boolean overdue;
}
