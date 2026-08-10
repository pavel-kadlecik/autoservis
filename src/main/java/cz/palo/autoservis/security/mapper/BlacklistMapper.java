package cz.palo.autoservis.security.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * MyBatis mapper tabulky {@code security.token_blacklist}.
 *
 * <p>Tokeny se na blacklist přidávají při odhlášení a záznam je relevantní až do
 * přirozené expirace JWT. Prošlé záznamy periodicky maže
 * {@link cz.palo.autoservis.security.service.BlacklistCleanupService}.
 *
 * <p><strong>V4 (analyza-2026-07):</strong> mapper ukládá doslova to, co dostane —
 * je odpovědností volajícího předat SHA-256 hash tokenu, ne surový JWT.
 * Viz {@code AuthenticationService.logout} (save) a {@code JwtAuthenticationFilter}
 * (isBlacklisted) — oba před voláním hashují přes
 * {@link cz.palo.autoservis.security.service.TokenHasher}.
 */
@Mapper
public interface BlacklistMapper {

    /**
     * Přidá token na blacklist. Idempotentní — opakované odhlášení stejným tokenem
     * (dvojklik, retry po výpadku sítě, odhlášení ze dvou tabů) je no-op místo porušení
     * primárního klíče (TD-53). {@code token} je PK, takže {@code ON CONFLICT DO NOTHING}
     * prostě ponechá existující řádek.
     *
     * @param token SHA-256 hex otisk JWT access tokenu ke zneplatnění (viz Javadoc třídy)
     */
    @Insert("INSERT INTO security.token_blacklist (token) VALUES (#{token}) ON CONFLICT (token) DO NOTHING")
    void save(String token);

    /**
     * Zjistí, zda je token na blacklistu.
     *
     * @param token SHA-256 hex otisk kontrolovaného JWT access tokenu (viz Javadoc třídy)
     * @return {@code true}, pokud je token na blacklistu
     */
    @Select("SELECT COUNT(*) > 0 FROM security.token_blacklist WHERE token = #{token}")
    boolean isBlacklisted(String token);

    /**
     * Smaže všechny záznamy blacklistu zneplatněné před daným časem.
     * Volá se periodicky pro odstranění záznamů tokenů, které už stejně expirovaly.
     *
     * @param cutoffTime záznamy starší než tento timestamp se smažou
     */
    @Delete("DELETE FROM security.token_blacklist WHERE invalidated_at < #{cutoffTime}")
    void deleteTokensOlderThan(LocalDateTime cutoffTime);
}
