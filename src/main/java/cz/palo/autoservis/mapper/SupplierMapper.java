package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SupplierMapper {

    /**
     * Najde dodavatele podle jeho ID.
     *
     * @param id jednoznačný identifikátor dodavatele
     * @return {@code Optional} s dodavatelem, nebo prázdný {@code Optional}, když nebyl nalezen
     */
    Optional<Supplier> findById(@Param("id") Long id);

    /**
     * Hledá dodavatele s dynamickými filtry a stránkováním.
     *
     * @param params parametry hledání — filtry, stránka a velikost stránky
     * @return seznam odpovídajících dodavatelů
     */
    List<Supplier> search(@Param("params") SupplierSearchParams params);

    /**
     * Spočítá celkový počet dodavatelů odpovídajících parametrům hledání.
     *
     * @param params parametry hledání včetně filtrů (např. jen aktivní)
     * @return celkový počet odpovídajících dodavatelů
     */
    long countSearch(@Param("params") SupplierSearchParams params);

    /**
     * Aktualizuje existující záznam dodavatele v databázi.
     *
     * @param supplier objekt dodavatele s novými hodnotami; musí obsahovat {@code id}
     * @return počet ovlivněných řádků (0 = nenalezen, 1 = aktualizován)
     */
    int update(Supplier supplier);


    boolean existsByRegistrationNumber(@Param("id") Long id ,@Param("registrationNumber") String registrationNumber);

    /**
     * Soft-delete dodavatele (nastaví {@code is_active = FALSE}).
     *
     * @param id ID dodavatele
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int deactivate(@Param("id") Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného dodavatele (nastaví {@code is_active = TRUE}).
     *
     * @param id ID dodavatele
     * @return počet ovlivněných řádků (0 = nenalezen)
     */
    int activate(@Param("id") Long id);

}
