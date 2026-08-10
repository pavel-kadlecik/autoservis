package cz.palo.autoservis.security.service;

import cz.palo.autoservis.security.mapper.BlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Plánovaná služba periodicky odstraňující expirované tokeny z JWT blacklistu.
 *
 * <p>Tokeny se na blacklist přidávají při odhlášení a zůstávají tam do své
 * přirozené expirace. Jakmile expirace JWT uplyne, systém token nepřijme
 * bez ohledu na blacklist, takže záznam lze bezpečně smazat.
 *
 * <p>Úklid běží každou hodinu a maže všechny záznamy blacklistu starší než
 * nakonfigurovaná životnost access tokenu ({@code jwt.expiration}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlacklistCleanupService {

    private final BlacklistMapper blacklistMapper;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    /**
     * Smaže záznamy blacklistu pro tokeny, které už expirovaly.
     *
     * <p>Mezní čas se počítá jako {@code now - jwt.expiration}. Každý token
     * blacklistovaný před tímto bodem už expiroval a lze ho bezpečně odstranit.
     * Běží každou hodinu (3 600 000 ms).
     */
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void cleanupOldTokens() {
        LocalDateTime cutoffTime = LocalDateTime.now().minus(jwtExpirationMs, ChronoUnit.MILLIS);
        log.debug("Blacklist cleanup: removing tokens blacklisted before {}", cutoffTime);
        blacklistMapper.deleteTokensOlderThan(cutoffTime);
        log.info("Blacklist cleanup completed");
    }
}
