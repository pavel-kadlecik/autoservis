package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.domain.order.OrderItemSummary;
import cz.palo.autoservis.model.dto.order.OrderItemDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code order.order_items}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/OrderItemMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null.</li>
 * </ul>
 */
@Mapper
public interface OrderItemMapper {

    /**
     * Vloží novou položku zakázky. Po úspěšném INSERTu se vygenerovaný PK
     * zapíše zpět do {@code orderItem.id} přes {@code useGeneratedKeys}.
     *
     * @param item nová položka zakázky (id musí být null)
     */
    void insert(OrderItem item);

    /**
     * Aktualizuje existující položku zakázky. Dynamický UPDATE — mění se jen non-null pole.
     *
     * @param item položka zakázky s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezena, 1 = úspěch)
     */
    int update(OrderItem item);

    /**
     * Trvale smaže položku zakázky podle ID.
     *
     * @param id ID položky zakázky
     * @return počet ovlivněných řádků (0 = nenalezena, 1 = úspěch)
     */
    int delete(@Param("id") Long id);

    /**
     * Vrací všechny položky dané zakázky, seřazené podle pozice.
     *
     * @param orderId ID zakázky
     * @return seznam položek zakázky
     */
    List<OrderItem> findByOrderId(@Param("orderId") Long orderId);

    /**
     * Položky zakázky, které drží materiál ze skladu, ale ze skladu ještě neodešly —
     * tedy pouhé rezervace čekající na výdej (V83).
     *
     * <p>Nevydaná = součet skladových pohybů té položky je nula. Vratka výdej odečte,
     * takže sem spadne i položka, jejíž výdej se vrátil při znovuotevření zakázky
     * a má se tedy vydat znovu.
     *
     * @param orderId ID zakázky
     * @return položky k výdeji seřazené podle pozice; prázdný seznam, není-li co vydat
     */
    List<OrderItem> findReservedByOrderId(@Param("orderId") Long orderId);

    /**
     * Opak předchozího: položky, které materiál ze skladu skutečně <strong>drží</strong> —
     * už byl vydán a zatím se nevrátil (V83).
     *
     * <p>Podle toho se rozhoduje, jestli jde zakázku zrušit. Pouhá rezervace zrušení
     * nebrání — díl leží v regálu a slib se uvolní sám. Blokovat musí jen materiál, který
     * fyzicky odešel a je potřeba rozhodnout, co se s ním stalo.
     *
     * @param orderId ID zakázky
     * @return položky držící vydaný materiál; prázdný seznam, když zakázka žádný nedrží
     */
    List<OrderItem> findIssuedByOrderId(@Param("orderId") Long orderId);

    /**
     * Najde položku zakázky podle ID.
     *
     * @param id ID položky zakázky
     * @return položka v {@link Optional}, nebo prázdný, když nebyla nalezena
     */
    Optional<OrderItem> findById(@Param("id") Long id);

    /**
     * Přeuspořádá položky dané zakázky podle zadaných pozic.
     *
     * @param orderId ID zakázky, jejíž položky se mají přeuspořádat
     * @param items   seznam položek s novými pozicemi určujícími nové pořadí
     */
    void reorder (@Param("orderId") Long orderId, @Param("items") List<OrderItemDto.ReorderRequest> items);

    /**
     * Najde nejvyšší hodnotu pozice mezi položkami dané zakázky.
     *
     * @param orderId ID zakázky, jejíž maximální pozice se zjišťuje
     * @return nejvyšší pozice položek zakázky, nebo 0, když žádné položky nejsou
     */
    int findMaxPositionByOrderId(@Param("orderId") Long orderId);

    Optional<OrderItemSummary> findSummaryByOrderId(@Param("orderId") Long orderId);

}
