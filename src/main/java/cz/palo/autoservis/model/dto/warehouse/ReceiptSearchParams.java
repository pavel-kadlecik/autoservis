package cz.palo.autoservis.model.dto.warehouse;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.dto.pagination.SearchParams;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Parametry hledání pro seznamový endpoint příjemek.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class ReceiptSearchParams extends SearchParams {

    /**
     * Výchozí řazení seznamu — naposledy importované doklady první.
     *
     * Default patří sem, ne do <otherwise> v XML: tam by jako jediný
     * ignoroval sortDesc a nebyl by z Javy vidět (U3R.1).
     */
    public ReceiptSearchParams() {
        setSortBy("createdAt");
        setSortDesc(true);
    }


    /** Filtr podle stavu workflow (null = všechny). */
    private ReceiptStatus status;

    /** Filtr podle typu dokladu (null = všechny). */
    private DocumentType documentType;

    /** Rozsah data vystavení (včetně krajů, obojí volitelné). */
    private LocalDate dateFrom;
    private LocalDate dateTo;
}
