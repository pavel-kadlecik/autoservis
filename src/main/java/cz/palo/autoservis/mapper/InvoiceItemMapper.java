package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.InvoiceItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code billing.invoice_items}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/InvoiceItemMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 *   <li>Dotazy na jeden záznam vracejí {@link Optional}, aby volající nemusel kontrolovat null.</li>
 * </ul>
 */
@Mapper
public interface InvoiceItemMapper {

    /**
     * Vloží jednu položku faktury. Po úspěšném INSERTu se vygenerovaný PK
     * zapíše zpět do {@code item.id} přes {@code useGeneratedKeys}.
     * Hlídané (TD-58): vloží se, jen když je nadřazená faktura v {@code DRAFT}.
     *
     * @param item nová položka faktury (id musí být null)
     * @return počet vložených řádků (0 = nadřazená faktura už není DRAFT)
     */
    int insert(InvoiceItem item);

    /**
     * Vloží víc položek faktury jedním SQL příkazem kvůli výkonu.
     * Použij při zakládání faktury se všemi položkami najednou.
     *
     * @param items     seznam položek k vložení
     * @param invoiceId ID faktury, které položky patří
     */
    void insertBatch(@Param("items") List<InvoiceItem> items,
                     @Param("invoiceId") Long invoiceId);

    /**
     * Najde položku faktury podle ID.
     *
     * @param id ID položky faktury
     * @return položka v {@link Optional}, nebo prázdný, když nebyla nalezena
     */
    Optional<InvoiceItem> findById(@Param("id") Long id);

    /**
     * Vrací všechny položky dané faktury, seřazené podle pozice.
     *
     * @param invoiceId ID faktury
     * @return seznam položek faktury
     */
    List<InvoiceItem> findByInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * Aktualizuje existující položku faktury. Dynamický UPDATE — mění se jen
     * non-null pole. Povoleno, jen dokud je nadřazená faktura ve stavu {@code DRAFT}.
     *
     * @param item položka faktury s novými hodnotami
     * @return počet ovlivněných řádků (0 = nenalezena, 1 = úspěch)
     */
    int update(InvoiceItem item);

    /**
     * Trvale smaže položku faktury podle ID.
     * Hlídané (TD-58): smaže se, jen když je nadřazená faktura v {@code DRAFT}.
     *
     * @param id ID položky faktury
     * @return počet smazaných řádků (0 = nenalezena, nebo faktura už není DRAFT)
     */
    int deleteById(@Param("id") Long id);
}
