package cz.palo.autoservis.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Maska číselné řady dokladů — faktur (V71) a pokladních dokladů (V92); číslování
 * dle masky à la iDoklad/Fakturoid.
 *
 * <p>Tokeny ve složených závorkách, vše ostatní jsou literály:
 * <ul>
 *   <li>{@code {RRRR}} / {@code {RR}} — rok (4 / 2 číslice) z data vystavení</li>
 *   <li>{@code {MM}} — měsíc (2 číslice)</li>
 *   <li>{@code {N}}, {@code {NN}}, {@code {NNN}}… — pořadové číslo; počet {@code N}
 *       určuje šířku doplněnou nulami (právě jeden výskyt povinný)</li>
 * </ul>
 *
 * <p>Reset řady plyne z masky sám: obsahuje-li {@code {MM}}, je řada měsíční;
 * jinak s rokem roční; bez data nekonečná — {@link #regex(LocalDate)} totiž peče
 * konkrétní rok/měsíc daného data přímo do vzoru, takže „stejná řada" znamená
 * „stejný vzor". Pořadí se neodvozuje z čítače, ale z {@code MAX+1} přes existující
 * čísla odpovídající vzoru — ruční zásah do čísla (v mezích masky) tak řadu posune.
 *
 * <p>Bez závislosti na Springu, aby šla logika testovat jako čistá jednotka
 * (stejný důvod jako {@link SpaydBuilder}).
 */
public final class DocumentNumberMask {

    /** Maximální délka čísla dokladu — musí sedět s VARCHAR(20) v DB (V71, V92). */
    public static final int MAX_NUMBER_LENGTH = 20;

    /** Maximální délka masky — musí sedět s VARCHAR(40) v DB (V71, V92). */
    public static final int MAX_MASK_LENGTH = 40;

    private sealed interface Part permits Literal, YearToken, MonthToken, SeqToken {}

    private record Literal(String text) implements Part {}
    private record YearToken(boolean fourDigits) implements Part {}
    private record MonthToken() implements Part {}
    private record SeqToken(int width) implements Part {}

    private final String source;
    private final List<Part> parts;

    private DocumentNumberMask(String source, List<Part> parts) {
        this.source = source;
        this.parts = parts;
    }

    /**
     * Rozparsuje a zvaliduje masku.
     *
     * @throws IllegalArgumentException s českou hláškou pro uživatele, když maska
     *         neprojde (prázdná, neznámý token, chybějící/vícenásobná sekvence,
     *         neuzavřená závorka, výsledné číslo delší než {@value #MAX_NUMBER_LENGTH} znaků)
     */
    public static DocumentNumberMask parse(String mask) {
        if (mask == null || mask.isBlank()) {
            throw new IllegalArgumentException("Maska číselné řady nesmí být prázdná.");
        }
        String source = mask.trim();
        if (source.length() > MAX_MASK_LENGTH) {
            throw new IllegalArgumentException(
                    "Maska může mít maximálně " + MAX_MASK_LENGTH + " znaků.");
        }

        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int sequenceTokens = 0;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '}') {
                throw new IllegalArgumentException("Maska obsahuje „}“ bez otevírací závorky.");
            }
            if (c != '{') {
                literal.append(c);
                continue;
            }
            int end = source.indexOf('}', i);
            if (end < 0) {
                throw new IllegalArgumentException("Maska obsahuje neuzavřenou závorku „{“.");
            }
            if (!literal.isEmpty()) {
                parts.add(new Literal(literal.toString()));
                literal.setLength(0);
            }
            String token = source.substring(i + 1, end);
            switch (token) {
                case "RRRR" -> parts.add(new YearToken(true));
                case "RR"   -> parts.add(new YearToken(false));
                case "MM"   -> parts.add(new MonthToken());
                default -> {
                    if (!token.isEmpty() && token.chars().allMatch(ch -> ch == 'N')) {
                        parts.add(new SeqToken(token.length()));
                        sequenceTokens++;
                    } else {
                        throw new IllegalArgumentException("Neznámý token {" + token
                                + "} — povolené jsou {RRRR}, {RR}, {MM} a {N}, {NN}, {NNN}…");
                    }
                }
            }
            i = end;
        }
        if (!literal.isEmpty()) {
            parts.add(new Literal(literal.toString()));
        }

        if (sequenceTokens != 1) {
            throw new IllegalArgumentException("Maska musí obsahovat právě jeden token "
                    + "pořadového čísla {N}, {NN}, {NNN}…");
        }

        DocumentNumberMask parsed = new DocumentNumberMask(source, List.copyOf(parts));
        int baseLength = parsed.formattedLength();
        if (baseLength > MAX_NUMBER_LENGTH) {
            throw new IllegalArgumentException("Číslo podle této masky by mělo " + baseLength
                    + " znaků — maximum je " + MAX_NUMBER_LENGTH + ".");
        }
        return parsed;
    }

    /**
     * Složí číslo dokladu pro dané datum vystavení a pořadí v řadě.
     * Pořadí širší než šířka tokenu řadu neshodí — číslo se prostě prodlouží
     * (limit {@value #MAX_NUMBER_LENGTH} znaků hlídá volající).
     */
    public String format(LocalDate date, long sequence) {
        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            switch (part) {
                case Literal l    -> sb.append(l.text());
                case YearToken y  -> sb.append(y.fourDigits()
                        ? String.format("%04d", date.getYear())
                        : String.format("%02d", date.getYear() % 100));
                case MonthToken m -> sb.append(String.format("%02d", date.getMonthValue()));
                case SeqToken s   -> sb.append(String.format("%0" + s.width() + "d", sequence));
            }
        }
        return sb.toString();
    }

    /**
     * POSIX-kompatibilní regex (funguje v PostgreSQL {@code ~} / {@code regexp_match}
     * i v Javě) pro čísla téže řady a téhož období: rok/měsíc jsou zapečené jako
     * konkrétní číslice z {@code date}, pořadové číslo je jediná zachytávací skupina.
     * Skupina je omezená na 15 číslic, aby {@code ::BIGINT} v SQL nemohl přetéct.
     */
    public String regex(LocalDate date) {
        StringBuilder sb = new StringBuilder("^");
        for (Part part : parts) {
            switch (part) {
                case Literal l    -> sb.append(escapeRegex(l.text()));
                case YearToken y  -> sb.append(y.fourDigits()
                        ? String.format("%04d", date.getYear())
                        : String.format("%02d", date.getYear() % 100));
                case MonthToken m -> sb.append(String.format("%02d", date.getMonthValue()));
                case SeqToken s   -> sb.append("([0-9]{").append(s.width()).append(",15})");
            }
        }
        return sb.append("$").toString();
    }

    /** Ověří, že číslo odpovídá masce a období daného data vystavení. */
    public boolean matches(String documentNumber, LocalDate date) {
        return documentNumber != null
                && Pattern.compile(regex(date)).matcher(documentNumber).matches();
    }

    /**
     * Pořadové číslo z existujícího čísla dokladu, patří-li do řady daného období (V89).
     *
     * <p>Používá {@link #regex(LocalDate)}, tedy <strong>tentýž</strong> předpis, jakým se
     * čísla skládají — hlídání mezer se proto nemůže s generátorem rozejít. Číslo z jiného
     * období nebo mimo masku vrátí prázdno; taková čísla řadu neovlivňují.
     */
    public java.util.OptionalLong sequenceOf(String documentNumber, LocalDate date) {
        if (documentNumber == null) {
            return java.util.OptionalLong.empty();
        }
        var matcher = Pattern.compile(regex(date)).matcher(documentNumber);
        if (!matcher.matches()) {
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** Původní (trimovaný) zápis masky — pro chybové hlášky. */
    public String source() {
        return source;
    }

    /** Délka čísla, dokud pořadí nepřeteče šířku sekvenčního tokenu. */
    private int formattedLength() {
        int length = 0;
        for (Part part : parts) {
            length += switch (part) {
                case Literal l    -> l.text().length();
                case YearToken y  -> y.fourDigits() ? 4 : 2;
                case MonthToken m -> 2;
                case SeqToken s   -> s.width();
            };
        }
        return length;
    }

    /**
     * Escapuje literál pro POSIX ERE. {@link Pattern#quote} nelze použít —
     * {@code \Q…\E} PostgreSQL nezná a regex musí běžet v obou enginech.
     */
    private static String escapeRegex(String literal) {
        StringBuilder sb = new StringBuilder();
        for (char c : literal.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
