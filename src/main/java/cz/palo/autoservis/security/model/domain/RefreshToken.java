package cz.palo.autoservis.security.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Doménový objekt refresh tokenu uloženého v {@code security.refresh_tokens}.
 *
 * <p>Každý záznam odpovídá jedné aktivní session. Jeden uživatel může mít
 * víc platných refresh tokenů najednou (např. webový prohlížeč a mobilní
 * aplikace současně).
 *
 * <p>Tokeny se při odvolání nemažou — příznak {@code revoked} umožňuje detekci
 * útoků opakovaným použitím tokenu (použití už odvolaného tokenu signalizuje
 * potenciální únos session).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    /** UUID primární klíč — generuje aplikační vrstva před INSERTem. */
    private String id;

    /** Neprůhledná hodnota tokenu posílaná klientovi a přijímaná od něj. */
    private String token;

    /** ID vlastníka — FK na {@code security.users.id}. */
    private Long userId;

    /** Čas expirace. Token s tímto časem v minulosti je neplatný. */
    private LocalDateTime expiresAt;

    /**
     * Příznak odvolání. {@code true} znamená explicitní zneplatnění tokenu
     * (odhlášení nebo zjištěné opakované použití). Záznamy se uchovávají pro audit.
     */
    private boolean revoked;

    /** Čas vytvoření záznamu. */
    private LocalDateTime createdAt;
}
