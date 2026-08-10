package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams;

/**
 * Service pro správu dodavatelů (skladový modul).
 */
public interface SupplierService {

    /**
     * Vrátí stránkovaný seznam dodavatelů odpovídajících zadaným parametrům vyhledávání.
     *
     * @param params parametry vyhledávání (filtry, stránka, velikost stránky)
     * @return stránkovaná response
     */
    PagedResponse<SupplierDto.ListResponse> getPage(SupplierSearchParams params);

    /**
     * Vrátí kompletní detail dodavatele (identifikace, adresa, bankovní spojení, kontakty).
     *
     * @param id ID dodavatele
     * @return detail dodavatele
     */
    SupplierDto.DetailResponse getById(Long id);

    /**
     * Aktualizuje údaje dodavatele.
     *
     * @param id      ID dodavatele
     * @param request validovaný update request
     * @return detail aktualizovaného dodavatele
     */
    SupplierDto.DetailResponse update(Long id, SupplierDto.UpdateRequest request);

    /**
     * Deaktivuje dodavatele (soft-delete).
     *
     * @param id ID dodavatele
     * @return detail aktualizovaného dodavatele
     */
    SupplierDto.DetailResponse deactivate(Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného dodavatele.
     *
     * @param id ID dodavatele
     * @return detail aktualizovaného dodavatele
     */
    SupplierDto.DetailResponse activate(Long id);

}
