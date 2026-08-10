package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.InvoiceParty;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper tabulky {@code billing.invoice_party}.
 *
 * <p>Veškeré SQL žije v {@code mapper/InvoicePartyMapper.xml}.
 */
@Mapper
public interface InvoicePartyMapper {

    /**
     * Vloží jednu zmrazenou stranu faktury (dodavatele nebo odběratele).
     *
     * @param party snapshot strany (id musí být null)
     */
    void insert(InvoiceParty party);

    /**
     * Vrací všechny strany faktury (typicky dodavatele a odběratele),
     * seřazené podle role.
     *
     * @param invoiceId ID faktury
     * @return seznam stran (může být prázdný)
     */
    List<InvoiceParty> findByInvoiceId(@Param("invoiceId") Long invoiceId);
}
