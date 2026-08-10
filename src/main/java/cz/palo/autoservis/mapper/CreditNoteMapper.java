package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.CreditNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper tabulky {@code billing.credit_notes} (opravný daňový doklad). SQL v {@code CreditNoteMapper.xml}.
 */
@Mapper
public interface CreditNoteMapper {

    void insert(CreditNote creditNote);

    Optional<CreditNote> findById(@Param("id") Long id);

    List<CreditNote> findByOriginalInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * Guardovaný přechod stavu (jako u faktury). Číslo přidělí DB trigger při přechodu do ISSUED.
     *
     * @return počet dotčených řádků (0 = stav už nesedí, 1 = úspěch)
     */
    int updateStatus(@Param("id") Long id,
                     @Param("status") cz.palo.autoservis.model.enums.InvoiceStatus status,
                     @Param("expectedStatus") cz.palo.autoservis.model.enums.InvoiceStatus expectedStatus);

    /**
     * Smaže <strong>koncept</strong> opravného dokladu (výjimka z R-06, stejně jako u faktury:
     * koncept nemá číslo řady {@code OD} a není dokladem, takže není co archivovat).
     *
     * <p>Bez mazání byla omylem založená oprava slepou uličkou: vystavit ji obsluha nechce,
     * zahodit nemohla, a nový dobropis k téže faktuře založit nešel — {@code INVOICE_ALREADY_CREDITED}
     * bere v potaz i koncepty.
     *
     * <p>Guard {@code WHERE status = 'DRAFT'} chrání vystavený doklad i při souběhu.
     *
     * @param id ID dobropisu
     * @return počet smazaných řádků (0 = nenalezen nebo už není DRAFT, 1 = úspěch)
     */
    int deleteDraft(@Param("id") Long id);
}
