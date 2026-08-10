package cz.palo.autoservis.service;

/**
 * Tisková podoba zakázky — <strong>zakázkový list</strong> (PDF, A4).
 *
 * <p>Doklad o převzetí vozu do servisu a o odsouhlaseném rozsahu a odhadu ceny (audit KN-28).
 * Není to daňový doklad: nemá číselnou řadu ani snapshoty stran, tiskne se ze živých dat zakázky.
 * Jediné, co musí zůstat neměnné, je stav tachometru při příjmu — ten je snímkem na zakázce (V70).
 */
public interface OrderDocumentService {

    /**
     * Vyrenderuje PDF zakázkového listu.
     *
     * @param orderId ID zakázky
     * @return PDF jako bajty
     */
    byte[] renderPdf(Long orderId);
}
