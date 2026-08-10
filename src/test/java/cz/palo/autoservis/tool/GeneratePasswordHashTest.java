package cz.palo.autoservis.tool;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Jednorázová OPS pomůcka — vygeneruje BCrypt hash hesla produkčního admina.
 *
 * <p>Nejde o skutečný test; {@code @Test} je jen proto, aby šel spustit přes Maven
 * Surefire bez dalšího pluginu. Bez zadaného hesla se přeskočí (běžný {@code ./mvnw test}
 * ho tedy neshodí). Nepotřebuje Docker ani Spring kontext — jen encoder.
 *
 * <p>Použití (vygeneruje hash pro {@code ADMIN_PASSWORD_HASH} v {@code /opt/autoservis/.env}):
 * <pre>
 *   ./mvnw test -Dtest=GeneratePasswordHashTest -DfailIfNoTests=false -Dadmin.password=MojeSilneHeslo
 * </pre>
 * Hash se vypíše do konzole a zároveň zapíše do {@code target/admin_password_hash.txt}
 * (Surefire stdout někdy odklání do reportu — soubor je spolehlivý zdroj).
 *
 * <p>Cost odpovídá {@code SecurityConfig.passwordEncoder()} (dnes default = 10). Pokud tam
 * sílu změníš (např. na 12), uprav i zdejší encoder, ať se počáteční hash chová stejně
 * jako hashe počítané aplikací při změně hesla.
 */
class GeneratePasswordHashTest {

    @Test
    void generateAdminPasswordHash() throws Exception {
        String password = System.getProperty("admin.password");
        Assumptions.assumeTrue(
                password != null && !password.isBlank(),
                "Přeskočeno — spusť s -Dadmin.password=<heslo> pro vygenerování hashe admina.");

        String hash = new BCryptPasswordEncoder().encode(password);

        Path out = Path.of("target", "admin_password_hash.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, hash + System.lineSeparator());

        System.out.println();
        System.out.println("=== ADMIN_PASSWORD_HASH (vlož do /opt/autoservis/.env) ===");
        System.out.println(hash);
        System.out.println("=== (zapsáno i do " + out.toAbsolutePath() + ") ===");
        System.out.println();
    }
}
