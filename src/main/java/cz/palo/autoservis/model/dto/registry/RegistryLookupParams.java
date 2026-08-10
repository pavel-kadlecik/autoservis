package cz.palo.autoservis.model.dto.registry;

/**
 * Parametry hledání v registru. API přijímá libovolnou kombinaci {@code vin},
 * {@code tp} (technický průkaz) a {@code orv} (osvědčení o registraci vozidla);
 * víc parametrů se kombinuje jako AND. Alespoň jeden musí být vyplněn —
 * validuje service vrstva.
 *
 * @param vin 17znakový identifikační kód vozidla, nebo {@code null}
 * @param tp  číslo technického průkazu, nebo {@code null}
 * @param orv číslo osvědčení o registraci, nebo {@code null}
 */
public record RegistryLookupParams(String vin, String tp, String orv) {

    /** Pohodlná factory pro běžné hledání jen podle VIN (obnovení dat vozidla). */
    public static RegistryLookupParams ofVin(String vin) {
        return new RegistryLookupParams(vin, null, null);
    }

    /** Vrací {@code true}, když žádný parametr nenese použitelnou hodnotu. */
    public boolean isEmpty() {
        return isBlank(vin) && isBlank(tp) && isBlank(orv);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
