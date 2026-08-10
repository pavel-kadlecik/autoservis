package cz.palo.autoservis.mapper;

import cz.palo.autoservis.model.domain.billing.CompanyProfile;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * MyBatis mapper jednořádkové tabulky {@code billing.company_profile}.
 *
 * <p>Veškeré SQL žije v {@code mapper/CompanyProfileMapper.xml}.
 */
@Mapper
public interface CompanyProfileMapper {

    /**
     * Vrací profil firmy (jediný řádek s {@code id = 1}).
     *
     * @return profil firmy, nebo prázdný Optional, pokud ještě nebyl nastaven
     */
    Optional<CompanyProfile> find();

    /**
     * Aktualizuje jediný řádek profilu firmy ({@code id = 1}).
     *
     * @param profile nové hodnoty (id se ignoruje; aktualizuje se řádek s id = 1)
     */
    void update(CompanyProfile profile);
}
