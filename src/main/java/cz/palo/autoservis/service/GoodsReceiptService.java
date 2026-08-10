package cz.palo.autoservis.service;


import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.warehouse.GoodReceiptAutocompleteParams;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;

import java.util.List;

public interface GoodsReceiptService {

    /**
     * Vrátí seznam naskladnitelných položek příjemky podle jejího ID.
     *
     * @param receiptId ID příjemky, jejíž naskladnitelné položky se mají vrátit
     * @return seznam naskladnitelných položek dané příjemky
     */
    List<GoodsReceiptItemDto.Response> getImportableItems(Long receiptId);

    /**
     * Našeptávač položek příjemek podle zadaných parametrů.
     *
     * @param params parametry našeptávače (hledaný text, limit výsledků, typ importu)
     * @return response se seznamem odpovídajících položek a příznakem, zda existují další výsledky
     */
    AutocompleteResponse autocomplete(GoodReceiptAutocompleteParams params);
}
