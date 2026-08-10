package cz.palo.autoservis.model.enums;

/**
 * Zdroj čísla pokladního dokladu — mapuje se na PostgreSQL ENUM
 * {@code billing.cash_receipt_number_source} (V93).
 *
 * <p>Řídí jen <strong>předvyplnění</strong> pole čísla v dialogu vystavení PPD — zapsat lze
 * v každém režimu libovolné unikátní číslo (týž kontrakt jako maska faktur, V71):
 * <ul>
 *   <li>{@link #MASK} — návrh skládá aplikace dle masky {@code cash_receipt_number_mask}
 *       (MAX+1 v řadě, zámek řady při vystavení);</li>
 *   <li>{@link #INVOICE} — dialog předvyplní číslo hrazené faktury (rozhodnutí uživatele
 *       2026-08-09: párování platby s fakturou je pro účetní zadarmo; vlastní souvislá řada
 *       PPD se nevede — hotově se platí jen některé faktury). Hlídání mezer PPD je v tomto
 *       režimu deaktivované, souvislost řady hlídá kontrola mezer faktur (V89);</li>
 *   <li>{@link #MANUAL} — pole zůstává prázdné, číslo píše obsluha.</li>
 * </ul>
 */
public enum CashReceiptNumberSource {
    MASK,
    INVOICE,
    MANUAL
}
