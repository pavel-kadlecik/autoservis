package cz.palo.autoservis.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hashování access tokenů před uložením do blacklistu (V4, analyza-2026-07) —
 * čistý unit test bez Spring kontextu.
 *
 * <p>Hash musí být <strong>stabilní napříč běhy i verzemi</strong>: zapisuje ho
 * {@code AuthenticationService.logout}, ale čte ho {@code JwtAuthenticationFilter} při každém
 * požadavku. Kdyby se algoritmus nebo kódování změnily, odhlášené tokeny by přestaly matchovat
 * a odhlášení by tiše přestalo fungovat — proto se tvrdí konkrétní známý digest, ne jen „něco vyšlo".
 */
class TokenHasherTest {

    /** Referenční vektor SHA-256("abc") — nezávislý na naší implementaci (FIPS 180-4). */
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("známý vstup dá známý digest (referenční vektor SHA-256)")
    void sha256Hex_knownVector() {
        assertThat(TokenHasher.sha256Hex("abc")).isEqualTo(SHA256_OF_ABC);
    }

    @Test
    @DisplayName("digest má 64 znaků a je malými písmeny — sloupec token je VARCHAR(512)")
    void sha256Hex_is64LowercaseHexChars() {
        String hash = TokenHasher.sha256Hex("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.signature");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("stejný token dá vždy stejný hash (zápis při logoutu = čtení ve filtru)")
    void sha256Hex_isDeterministic() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtYW5hZ2VyIn0.abc123";

        assertThat(TokenHasher.sha256Hex(token)).isEqualTo(TokenHasher.sha256Hex(token));
    }

    @Test
    @DisplayName("různé tokeny dají různý hash — jinak by logout odhlásil cizí session")
    void sha256Hex_differentTokensDifferentHashes() {
        String hashA = TokenHasher.sha256Hex("token-uzivatele-A");
        String hashB = TokenHasher.sha256Hex("token-uzivatele-B");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("tokeny lišící se jediným znakem dají zcela jiný hash")
    void sha256Hex_singleCharDifference_changesHash() {
        assertThat(TokenHasher.sha256Hex("token-1")).isNotEqualTo(TokenHasher.sha256Hex("token-2"));
    }

    @Test
    @DisplayName("hash NENÍ původní token — v DB nesmí skončit použitelný bearer token")
    void sha256Hex_doesNotContainOriginalToken() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.tajny-podpis";

        String hash = TokenHasher.sha256Hex(token);

        assertThat(hash).isNotEqualTo(token);
        assertThat(hash).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(hash).doesNotContain("tajny-podpis");
    }

    @Test
    @DisplayName("diakritika se hashuje v UTF-8, ne v platformním kódování")
    void sha256Hex_usesUtf8Encoding() {
        // Očekávaná hodnota spočítaná mimo aplikaci (sha256sum nad UTF-8 bajty řetězce "příliš").
        // Kdyby implementace vypustila StandardCharsets.UTF_8 a spolehla se na platformní
        // kódování, na Windows (Windows-1250) by vyšel jiný digest a test spadne.
        assertThat(TokenHasher.sha256Hex("příliš"))
                .isEqualTo("762f8be7b16d6318500e6665902ca054839112f2eff180cdf4136e05e53e7421");
    }
}
