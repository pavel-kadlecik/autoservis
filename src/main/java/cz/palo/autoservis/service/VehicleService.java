package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.vehicle.VehicleAutocompleteParams;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams;

import java.util.List;

/**
 * Service rozhraní správy vozidel.
 *
 * <p>Poskytuje CRUD operace, soft delete/reaktivaci a podporu našeptávače
 * nad tabulkou {@code vehicle.vehicles}.
 */
public interface VehicleService {

    /**
     * Vrací stránkovaný seznam aktivních vozidel odpovídajících parametrům hledání.
     *
     * @param params parametry hledání (filtry, stránka, velikost stránky)
     * @return stránkovaná response
     */
    PagedResponse<VehicleDto.ListResponse> getPage(VehicleSearchParams params);

    /**
     * Vrací plný detail aktivního vozidla podle ID.
     *
     * @param id ID vozidla
     * @return detailová response vozidla
     */
    VehicleDto.DetailResponse getById(Long id);

    /**
     * Založí nové vozidlo navázané na existujícího zákazníka.
     *
     * @param createRequest zvalidovaný request s daty vozidla
     * @param userId        ID právě přihlášeného uživatele (auditní pole {@code created_by})
     * @return detailová response založeného vozidla
     */
    VehicleDto.DetailResponse create(VehicleDto.CreateRequest createRequest, Long userId);

    /**
     * Upraví existující vozidlo.
     *
     * @param id            ID vozidla
     * @param updateRequest zvalidovaný request s novými hodnotami
     * @param userId        ID právě přihlášeného uživatele
     * @return detailová response upraveného vozidla
     */
    VehicleDto.DetailResponse update(Long id, VehicleDto.UpdateRequest updateRequest, Long userId);

    /**
     * Deaktivuje vozidlo (soft delete). Nastaví {@code is_active = FALSE}.
     * Záznam zůstává v databázi kvůli servisní historii.
     *
     * @param id ID vozidla
     * @return detailová response upraveného vozidla
     */
    VehicleDto.DetailResponse deactivate(Long id);

    /**
     * Deaktivuje všechna aktivní vozidla zákazníka (soft delete).
     * Volá se při deaktivaci vlastníka, aby platil invariant
     * „aktivní vozidlo ⇒ aktivní vlastník".
     *
     * @param customerId ID zákazníka
     * @return počet deaktivovaných vozidel
     */
    int deactivateByCustomerId(Long customerId);


    /**
     * Znovu aktivuje dříve deaktivované vozidlo. Nastaví {@code is_active = TRUE}.
     *
     * @param id ID vozidla
     * @return detailová response upraveného vozidla
     */
    VehicleDto.DetailResponse activate(Long id);

    /**
     * Vrací návrhy našeptávače pro vyhledávací pole vozidel.
     *
     * @param params parametry našeptávače (dotaz, limit)
     * @return autocomplete response s položkami a příznakem hasMore
     */
    AutocompleteResponse autocomplete(VehicleAutocompleteParams params);

    /**
     * Načte seznam vozidel navázaných na daného zákazníka.
     *
     * @param id ID zákazníka, jehož vozidla se mají načíst
     * @return seznam souhrnů vozidel daného zákazníka
     */
    List<VehicleDto.SummaryResponse> findByCustomerId(Long id);
}
