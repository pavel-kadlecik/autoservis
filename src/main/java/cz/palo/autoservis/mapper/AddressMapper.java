package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.customer.Address;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * MyBatis mapper rozhraní tabulky {@code customer.addresses}.
 *
 * <p>Konvence:
 * <ul>
 *   <li>Veškeré SQL je výhradně v {@code mapper/AddressMapper.xml}.</li>
 *   <li>Anotace typu {@code @Select} se nepoužívají — pro složitější dotazy má přednost XML.</li>
 * </ul>
 */
@Mapper
public interface AddressMapper {

    /**
     * Načte adresu podle jejího ID.
     *
     * @param id jednoznačný identifikátor hledané adresy
     * @return {@code Optional} s adresou, nebo prázdný {@code Optional}, když nebyla nalezena
     */
    Optional<Address> findById(@Param("id") Long id);

    /**
     * Vloží novou adresu.
     *
     * @param address doménový objekt adresy (id musí být null)
     * @return počet ovlivněných řádků
     */
    int insert(@Param("address") Address address);

    /**
     * Smaže všechny adresy zákazníka. Používá se pro full-replace úpravu sady
     * adres (TD-42) — volající vloží novou sadu v téže transakci.
     *
     * @param customerId ID zákazníka
     * @return počet smazaných řádků
     */
    int deleteByCustomerId(@Param("customerId") Long customerId);
}
