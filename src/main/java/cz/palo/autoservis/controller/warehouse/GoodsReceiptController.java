package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.dto.autocomplete.AutocompleteResponse;
import cz.palo.autoservis.model.dto.warehouse.GoodReceiptAutocompleteParams;
import cz.palo.autoservis.model.dto.warehouse.GoodsReceiptItemDto;
import cz.palo.autoservis.service.GoodsReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/{version}/warehouse/goods-receipts")
@RequiredArgsConstructor
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    /**
     * Vrací návrhy našeptávače potvrzených příjemek s nevyčerpanými položkami —
     * hledá se podle čísla faktury, nebo podle čísla objednávky (dle typu importu).
     * Slouží importu položek příjemky do zakázky, ne hledání produktů.
     *
     * @param params parametry našeptávače (hledaný řetězec, limit, typ importu)
     * @return 200 OK s položkami našeptávače
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<AutocompleteResponse> autocomplete(
            @Valid @ModelAttribute GoodReceiptAutocompleteParams params) {
        return ResponseEntity.ok(goodsReceiptService.autocomplete(params));
    }

    @GetMapping("/{id}/items")
   public ResponseEntity<List<GoodsReceiptItemDto.Response>> getImportableItems(@PathVariable Long id) {
              return ResponseEntity.ok(goodsReceiptService.getImportableItems(id));
    }

}
