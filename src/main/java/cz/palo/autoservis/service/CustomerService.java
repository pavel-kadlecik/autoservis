package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.customer.CustomerAutocompleteParams;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;

/**
 * Service rozhraní pro správu zákazníků.
 *
 * <p>Poskytuje CRUD operace, soft-delete/reaktivaci a podporu autocomplete
 * pro tabulku {@code customer.customers}.
 */
public interface CustomerService {

    /**
     * Vrátí kompletní detail zákazníka podle ID včetně adres a kontaktních osob.
     *
     * @param id ID zákazníka
     * @return detail zákazníka
     */
    CustomerDto.DetailResponse getById(Long id);

    /**
     * Vrátí stránkovaný seznam zákazníků odpovídajících zadaným parametrům vyhledávání.
     *
     * @param params parametry vyhledávání (filtry, stránka, velikost stránky)
     * @return stránkovaná response
     */
    PagedResponse<CustomerDto.ListResponse> getPage(CustomerSearchParams params);

    /**
     * Vytvoří nového zákazníka.
     *
     * @param createRequest validovaný request s daty zákazníka
     * @param userId        ID aktuálně přihlášeného uživatele (auditní pole {@code created_by})
     * @return detail vytvořeného zákazníka
     */
    CustomerDto.DetailResponse create(CustomerDto.CreateRequest createRequest, Long userId);

    /**
     * Aktualizuje existujícího zákazníka.
     *
     * @param id            ID zákazníka
     * @param updateRequest validovaný request s aktualizovanými poli
     * @param userId        ID aktuálně přihlášeného uživatele
     * @return detail aktualizovaného zákazníka
     */
    CustomerDto.DetailResponse update(Long id, CustomerDto.UpdateRequest updateRequest, Long userId);

    /**
     * Deaktivuje zákazníka (soft-delete). Nastaví {@code is_active = FALSE}.
     * Záznam zůstává v databázi kvůli servisní historii.
     *
     * @param id ID zákazníka
     * @return detail aktualizovaného zákazníka
     */
    CustomerDto.DetailResponse deactivate(Long id);

    /**
     * Znovu aktivuje dříve deaktivovaného zákazníka. Nastaví {@code is_active = TRUE}.
     *
     * @param id ID zákazníka
     * @return detail aktualizovaného zákazníka
     */
    CustomerDto.DetailResponse activate(Long id);

    /**
     * Vrátí návrhy našeptávače pro vyhledávací pole zákazníků.
     *
     * @param params parametry autocomplete (hledaný řetězec, limit)
     * @return autocomplete response s položkami a příznakem hasMore
     */
    AutocompleteResponse autocomplete(CustomerAutocompleteParams params);
}
