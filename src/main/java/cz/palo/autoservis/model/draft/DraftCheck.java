package cz.palo.autoservis.model.draft;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Výsledek jedné deterministické kontroly draftu („kód počítá").
 * position je vyplněná jen u řádkových kontrol (LINE_MATH).
 *
 * <p>Kromě výsledku ({@code ok}) nese i {@code independent} — jestli kontrola vůbec něco
 * dokázala. Doklad, který neuvádí všechny částky (typicky ručně psaný dodací list), si je nechá
 * dopočítat assemblerem a kontrola pak porovnává <strong>náš vlastní výpočet sám se sebou</strong>:
 * projde vždycky, ať byl podklad jakýkoli. Takové „ok" není ověření a nesmí se za ně vydávat
 * (audit KN-17).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftCheck {

    public static final String LINE_MATH = "LINE_MATH";
    public static final String LINES_SUM_VS_RECAP = "LINES_SUM_VS_RECAP";
    public static final String RECAP_SUM = "RECAP_SUM";
    public static final String SUBTOTAL_PLUS_VAT_EQ_TOTAL = "SUBTOTAL_PLUS_VAT_EQ_TOTAL";
    public static final String LINES_SUM_VS_TOTAL = "LINES_SUM_VS_TOTAL";
    public static final String ICO_CHECKSUM = "ICO_CHECKSUM";
    public static final String SUPPLIER_KNOWN = "SUPPLIER_KNOWN";

    private String code;
    private boolean ok;

    /**
     * {@code false} = kontrola porovnávala hodnotu, kterou jsme si sami dopočítali, s tím,
     * z čeho jsme ji dopočítali — tedy neprokázala nic. Pole se v takovém případě nepovyšují
     * na VERIFIED a doklad se nepovažuje za zrekonciliovaný.
     */
    private boolean independent;

    private Integer position;

    /** Kontrola s nezávislým protějškem (údaj z dokladu, kontrolní součet, záznam v DB). */
    public static DraftCheck of(String code, boolean ok) {
        return new DraftCheck(code, ok, true, null);
    }

    public static DraftCheck of(String code, boolean ok, boolean independent) {
        return new DraftCheck(code, ok, independent, null);
    }

    public static DraftCheck ofLine(String code, boolean ok, boolean independent, Integer position) {
        return new DraftCheck(code, ok, independent, position);
    }
}
