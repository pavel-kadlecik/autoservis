package cz.palo.autoservis.controller.warehouse;

import cz.palo.autoservis.model.dto.warehouse.StockValuationDto;
import cz.palo.autoservis.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller ocenění skladu (E3.2, P-4).
 *
 * <p>Base path: {@code /api/{version}/warehouse/stock-valuation}
 */
@RestController
@RequestMapping("/api/{version}/warehouse/stock-valuation")
@RequiredArgsConstructor
public class StockValuationController {

    private final ProductService productService;

    /**
     * Vrací hodnotu zásob v pořizovacích cenách (bez DPH): celkem plus rozpad
     * po produktech, spočteno ze zůstatků šarží v DB view.
     *
     * @return 200 OK s celkovou hodnotou a položkami
     */
    @GetMapping
    public ResponseEntity<StockValuationDto.Response> getStockValuation() {
        return ResponseEntity.ok(productService.getStockValuation());
    }
}
