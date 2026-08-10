package cz.palo.autoservis.model.dto.autocomplete;

import lombok.Getter;
import lombok.Setter;

/**
 * Jedna položka našeptávače.
 * {@code value} se zobrazuje v nabídce; {@code description} dodává kontext.
 */
@Getter
@Setter
public class AutocompleteItem {

    private Long id;

    /** Hlavní zobrazovaný popisek v nabídce (např. jméno zákazníka, VIN vozidla). */
    private String value;

    /** Vedlejší popisek dodávající kontext (např. číslo zákazníka, vlastník vozidla). */
    private String description;

    /**
     * Volitelný třetí řádek pod {@link #description}. Plní ho jen některé našeptávače —
     * vozidla ho používají pro VIN, takže nabídka unese SPZ, vlastníka i VIN najednou.
     * {@code null} znamená, že se řádek vůbec nevykreslí.
     */
    private String detail;
}
