package cz.palo.autoservis.model.dto.order;

import cz.palo.autoservis.model.dto.pagination.SearchParams;
import cz.palo.autoservis.model.enums.OrderStatus;
import lombok.Getter;

import java.util.List;
import lombok.Setter;

/**
 * Parametry hledání pro seznamový endpoint servisních zakázek.
 * Předává se jako {@code @Param("params")} do MyBatis XML mapperů.
 */
@Getter
@Setter
public class OrderSearchParams extends SearchParams {

    /**
     * Vychozi razeni seznamu - nejstarsi zakazky prvni - poradi, v jakem se otevrely.
     *
     * Default patri sem, ne do <otherwise> v XML: tam by jako jediny
     * ignoroval sortDesc a nebyl by z Javy videt (U3R.1).
     */
    public OrderSearchParams() {
        setSortBy("createdAt");
        setSortDesc(false);
    }

    /**
     * Když je vyplněno, vrací jen zakázky v některém z těchto stavů (prázdné = všechny).
     *
     * <p><strong>Více stavů najednou</strong> (V84): jeden stav nestačil, protože běžný
     * dotaz obsluhy zní „ukaž mi rozpracované", což jsou čtyři stavy zároveň. Frontend
     * je posílá jako opakovaný parametr {@code statuses=RECEIVED&statuses=DIAGNOSIS}.
     */
    private List<OrderStatus> statuses;

    /**
     * Když {@code true}, vrací jen zakázky „po termínu" — slíbený termín dokončení
     * ({@code estimated_completion_at}) už uplynul a zakázka není v terminálním stavu
     * (COMPLETED/CANCELLED). Analogie filtru „po splatnosti" u faktur (overdue).
     * Zakázky bez termínu se nepočítají (NULL &lt; now() není pravda).
     */
    private Boolean overdue;

    /**
     * Když je vyplněno, vrací jen zakázky tohoto vozidla — <strong>servisní historie vozu</strong>
     * (audit KN-27). Do jejího zavedení šlo na zakázky vozu dosáhnout jen fulltextem přes SPZ nebo
     * VIN, tedy oklikou, kterou obsluha nemá odkud vědět — a u vozu bez SPZ (pole je nullable)
     * musela sáhnout po VIN.
     *
     * <p>Filtr kolekce query parametrem je vzor z {@code konvence.md §10}
     * ({@code GET /vehicles?customerId=5}), ne porušení R-14: {@code id} v URL patří identifikaci
     * <em>resource</em>, tady jde o zúžení seznamu.
     */
    private Long vehicleId;

    /** Když je vyplněno, vrací jen zakázky tohoto zákazníka (napříč všemi jeho vozidly). */
    private Long customerId;

}
