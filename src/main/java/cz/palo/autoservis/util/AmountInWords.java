package cz.palo.autoservis.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Převod peněžní částky na česká slova pro tiskovou náležitost „slovy" (např. na příjmovém
 * pokladním dokladu). Bez externí knihovny — čeština do miliard je ohraničený, testovatelný problém.
 *
 * <p>Vrací slovní vyjádření <b>celých korun</b> (haléře cash doklad neřeší; pokud jsou nenulové,
 * doplní se za slovy jako „a XX/100"). Popisek „Kč" nese sám doklad, proto se koruny za číslo
 * nepřidávají — sladěno se vzorem „Slovy Kč: Dvacetdvatisíceosmdesáttři".
 *
 * <p>Výstup je <b>dohromady a s velkým počátečním písmenem</b> („Tisíctřistapadesátosm") — účetní
 * konvence pokladních dokladů proti vpisování mezi slova; pravopisné psaní s mezerami je pro tento
 * typ dokumentu právě ta výjimka, kde se slívá (viz docs/funkce/prijmovy-pokladni-doklad.md).
 * Interně se skládá po slovech a slití je poslední krok, ať zůstane skládání čitelné.
 *
 * <p>Skloňování měrových slov (tisíc/tisíce/tisíc, milion/miliony/milionů) se řídí gramatickým
 * pravidlem podle poslední číslice skupiny; číslovky 1 a 2 mají v pozici tisíců/milionů mužský
 * tvar (jeden tisíc, dva tisíce).
 */
public final class AmountInWords {

    private AmountInWords() {}

    private static final String[] TEENS = {
            "deset", "jedenáct", "dvanáct", "třináct", "čtrnáct",
            "patnáct", "šestnáct", "sedmnáct", "osmnáct", "devatenáct"
    };
    private static final String[] TENS = {
            "", "", "dvacet", "třicet", "čtyřicet",
            "padesát", "šedesát", "sedmdesát", "osmdesát", "devadesát"
    };
    private static final String[] HUNDREDS = {
            "", "sto", "dvě stě", "tři sta", "čtyři sta",
            "pět set", "šest set", "sedm set", "osm set", "devět set"
    };
    /** Základní tvar jednotek (finální skupina, ženský/neutrální kontext: „jedna", „dva"). */
    private static final String[] ONES = {
            "", "jedna", "dva", "tři", "čtyři", "pět", "šest", "sedm", "osm", "devět"
    };
    /** Mužský tvar jednotek pro pozici tisíců/milionů: „jeden tisíc", „dva tisíce". */
    private static final String[] ONES_MASC = {
            "", "jeden", "dva", "tři", "čtyři", "pět", "šest", "sedm", "osm", "devět"
    };

    private static final long MILLION = 1_000_000L;
    private static final long BILLION = 1_000_000_000L;

    /**
     * Převede částku na česká slova (celé koruny) — dohromady, s velkým počátečním písmenem.
     *
     * @param amount částka (nesmí být null ani záporná)
     * @return slovní vyjádření, např. {@code "Dvacetdvatisíceosmdesáttři"}
     * @throws IllegalArgumentException pro null, zápornou částku nebo částku ≥ 1&nbsp;000&nbsp;000&nbsp;000
     */
    public static String toWords(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount nesmí být null");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount nesmí být záporná: " + amount);
        }

        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        long crowns = rounded.longValue();
        int hellers = rounded.remainder(BigDecimal.ONE)
                .movePointRight(2).abs().intValue();

        if (crowns >= BILLION) {
            throw new IllegalArgumentException("amount je mimo podporovaný rozsah (< 1 miliarda): " + amount);
        }

        String words = crownsToWords(crowns).replace(" ", "");
        words = Character.toUpperCase(words.charAt(0)) + words.substring(1);
        if (hellers > 0) {
            words += " a " + String.format("%02d", hellers) + "/100";
        }
        return words;
    }

    private static String crownsToWords(long n) {
        if (n == 0) {
            return "nula";
        }

        StringBuilder sb = new StringBuilder();

        long millions = n / MILLION;
        long thousands = (n / 1000) % 1000;
        int units = (int) (n % 1000);

        if (millions > 0) {
            appendScaledGroup(sb, (int) millions, "milion", "miliony", "milionů");
        }
        if (thousands > 0) {
            appendScaledGroup(sb, (int) thousands, "tisíc", "tisíce", "tisíc");
        }
        if (units > 0) {
            appendWord(sb, threeDigits(units, ONES));
        }

        return sb.toString();
    }

    /** Připojí skupinu tisíců/milionů i s odpovídajícím měrovým slovem (mužský tvar jednotek). */
    private static void appendScaledGroup(StringBuilder sb, int value, String one, String few, String many) {
        if (value == 1) {
            appendWord(sb, one);                       // „tisíc", „milion" (ne „jeden tisíc")
        } else {
            appendWord(sb, threeDigits(value, ONES_MASC));
            appendWord(sb, scaleWord(value, one, few, many));
        }
    }

    /** Slovní tvar tří číslic (1–999) — stovky, desítky, jednotky ve zvoleném tvaru jednotek. */
    private static String threeDigits(int v, String[] ones) {
        StringBuilder sb = new StringBuilder();

        int h = v / 100;
        int rest = v % 100;

        if (h > 0) {
            appendWord(sb, HUNDREDS[h]);
        }
        if (rest >= 10 && rest <= 19) {
            appendWord(sb, TEENS[rest - 10]);
        } else {
            int t = rest / 10;
            int o = rest % 10;
            if (t > 0) {
                appendWord(sb, TENS[t]);
            }
            if (o > 0) {
                appendWord(sb, ones[o]);
            }
        }
        return sb.toString();
    }

    /** Skloňování měrového slova podle počtu (2–4 → few, jinak many; poslední číslice, mimo 11–14). */
    private static String scaleWord(int count, String one, String few, String many) {
        int mod100 = count % 100;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        int last = count % 10;
        if (last == 1) {
            return many;   // 21, 31, … → genitiv množný („dvacet jedna tisíc")
        }
        if (last >= 2 && last <= 4) {
            return few;    // 22, 33, … → „tisíce", „miliony"
        }
        return many;
    }

    private static void appendWord(StringBuilder sb, String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(word);
    }
}
