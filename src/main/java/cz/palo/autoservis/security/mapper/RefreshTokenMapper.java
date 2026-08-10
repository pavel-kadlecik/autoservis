package cz.palo.autoservis.security.mapper;

import cz.palo.autoservis.security.model.domain.RefreshToken;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code security.refresh_tokens}.
 *
 * <p>Refresh tokeny se nikdy nemažou — jen odvolávají. To umožňuje detekci
 * útoků opakovaným použitím tokenu: když se už odvolaný token použije znovu,
 * server může rozpoznat potenciální únos session a odvolat všechny session
 * uživatele.
 */
@Mapper
public interface RefreshTokenMapper {

    /**
     * Uloží nový refresh token.
     * ID generuje aplikační vrstva před voláním této metody.
     *
     * @param refreshToken token k uložení
     */
    @Insert("""
            INSERT INTO security.refresh_tokens (id, token, user_id, expires_at, revoked, created_at)
            VALUES (#{id}, #{token}, #{userId}, #{expiresAt}, #{revoked}, #{createdAt})
            """)
    void save(RefreshToken refreshToken);

    /**
     * Najde refresh token podle jeho hodnoty.
     * Používá se při obnově tokenů k ověření tokenu zaslaného klientem.
     *
     * @param token hodnota refresh tokenu od klienta
     * @return token v {@link Optional}, nebo prázdný, když nebyl nalezen
     */
    @Select("""
            SELECT id, token, user_id, expires_at, revoked, created_at
            FROM security.refresh_tokens
            WHERE token = #{token}
            """)
    Optional<RefreshToken> findByToken(String token);

    /**
     * Odvolá všechny aktivní refresh tokeny daného uživatele.
     * Používá se při odhlášení ze všech zařízení, změně hesla
     * nebo zjištěném bezpečnostním incidentu.
     *
     * @param userId ID uživatele, jehož tokeny se mají odvolat
     */
    @Update("""
            UPDATE security.refresh_tokens
            SET revoked = TRUE
            WHERE user_id = #{userId}
              AND revoked = FALSE
            """)
    void revokeAllByUserId(Long userId);

    /**
     * Odvolá jediný refresh token podle jeho hodnoty.
     * Používá se při odhlášení z jednoho zařízení — ostatní session zůstávají aktivní.
     *
     * @param token hodnota refresh tokenu k odvolání
     */
    @Update("""
            UPDATE security.refresh_tokens
            SET revoked = TRUE
            WHERE token = #{token}
            """)
    void revokeByToken(String token);
}
