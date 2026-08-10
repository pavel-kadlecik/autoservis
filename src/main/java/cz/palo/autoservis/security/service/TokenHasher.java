package cz.palo.autoservis.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bezstavová utilita hashující JWT access tokeny před uložením do
 * {@code security.token_blacklist} i před hledáním v něm.
 *
 * <p><strong>Proč (V4, analyza-2026-07):</strong> blacklist dřív ukládal surový JWT.
 * Uniklá záloha databáze by pak obsahovala živé, použitelné access tokeny až do jejich
 * přirozené expirace. Uložení SHA-256 otisku znamená, že samotný únik DB na
 * rekonstrukci použitelného tokenu nestačí — otisk je jednosměrný a původní JWT
 * (bearer credential) se nikdy neperzistuje.
 *
 * <p>Hash používají jen access tokeny. Refresh tokeny jsou už tak neprůhledná,
 * kryptograficky náhodná UUID uložená v jiné tabulce k jinému účelu — jejich
 * hashování je mimo rozsah.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    /**
     * Spočítá SHA-256 otisk tokenu, zakódovaný jako 64znakový hex řetězec malými písmeny.
     *
     * @param token surový JWT access token
     * @return 64znakový hex otisk
     */
    public static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 není k dispozici", e);
        }
    }
}
