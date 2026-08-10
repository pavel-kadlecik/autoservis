package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;

/**
 * Service pro čtení a aktualizaci identity vystavující firmy (dodavatele na dokladech).
 */
public interface CompanyProfileService {

    /**
     * Vrátí aktuální profil firmy.
     *
     * @return response s profilem firmy
     */
    CompanyProfileDto.Response get();

    /**
     * Aktualizuje profil firmy.
     *
     * @param request validovaný update request
     * @return response s aktualizovaným profilem firmy
     */
    CompanyProfileDto.Response update(CompanyProfileDto.UpdateRequest request);
}
