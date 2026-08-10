package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CRUD skladových karet ({@code ProductServiceImpl}) — doplňuje existující testy skladu
 * ({@code ProductUnitValidationTest} — jednotky, {@code ProductDeactivationTest} — zásoba,
 * {@code ManualStockMovementTest} — pohyby), které tyto cesty nepokrývají.
 *
 * <p>Těžiště: jedinečnost SKU (obě větve — kolize s cizí kartou vs. ponechání vlastního SKU),
 * reaktivace karty a 404 na neznámé ID.
 */
@Transactional
class ProductCrudServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    // =========================================================================
    // create
    // =========================================================================

    @Test
    @DisplayName("create založí kartu jako aktivní, s nulovou zásobou a bez šarží")
    void create_persistsActiveCardWithoutStock() {
        ProductDto.DetailResponse created = productService.create(createRequest("SKU-T2-001", "Olejový filtr"));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getSku()).isEqualTo("SKU-T2-001");
        assertThat(created.getName()).isEqualTo("Olejový filtr");
        assertThat(created.getManufacturer()).isEqualTo("Mann");
        assertThat(created.getSalePrice()).isEqualByComparingTo("199.00");
        assertThat(created.getMinStockLevel()).isEqualByComparingTo("5");
        assertThat(created.getDefaultVatRate()).isEqualTo(21);
        assertThat(created.getActive()).isTrue();
        assertThat(created.getQuantityOnHand()).isEqualByComparingTo("0");
        assertThat(created.getBatches()).isEmpty();
        assertThat(created.getMovements()).isEmpty();
    }

    @Test
    @DisplayName("nová karta bez zásoby je pod minimem (minimum vyplněné, zásoba 0)")
    void create_withMinStockLevel_isImmediatelyLowStock() {
        ProductDto.DetailResponse created = productService.create(createRequest("SKU-T2-002", "Vzduchový filtr"));

        assertThat(created.getLowStock()).isTrue();
    }

    @Test
    @DisplayName("create s obsazeným SKU → DUPLICATE_SKU (422)")
    void create_duplicateSku_throwsBusinessRule() {
        productService.create(createRequest("SKU-T2-003", "První karta"));

        assertThatThrownBy(() -> productService.create(createRequest("SKU-T2-003", "Druhá karta")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("DUPLICATE_SKU");
                    assertThat(e.getField()).isEqualTo("sku");
                    assertThat(e.getParams()).containsEntry("sku", "SKU-T2-003");
                });
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update přepíše údaje karty a vrátí čerstvý stav")
    void update_overwritesFields() {
        Long id = productService.create(createRequest("SKU-T2-004", "Původní název")).getId();

        ProductDto.UpdateRequest request = updateRequest("SKU-T2-004", "Nový název");
        request.setManufacturer("Bosch");
        request.setSalePrice(new BigDecimal("249.00"));
        request.setMinStockLevel(new BigDecimal("8"));

        ProductDto.DetailResponse updated = productService.update(id, request);

        assertThat(updated.getName()).isEqualTo("Nový název");
        assertThat(updated.getManufacturer()).isEqualTo("Bosch");
        assertThat(updated.getSalePrice()).isEqualByComparingTo("249.00");
        assertThat(updated.getMinStockLevel()).isEqualByComparingTo("8");
        assertThat(productService.getById(id).getName()).isEqualTo("Nový název");
    }

    @Test
    @DisplayName("update na SKU jiné karty → DUPLICATE_SKU (422)")
    void update_skuTakenByAnotherProduct_throwsBusinessRule() {
        productService.create(createRequest("SKU-T2-005", "Cizí karta"));
        Long id = productService.create(createRequest("SKU-T2-006", "Moje karta")).getId();

        assertThatThrownBy(() -> productService.update(id, updateRequest("SKU-T2-005", "Moje karta")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_SKU"));
    }

    @Test
    @DisplayName("update ponechávající vlastní SKU projde (nekoliduje sám se sebou)")
    void update_keepingOwnSku_succeeds() {
        Long id = productService.create(createRequest("SKU-T2-007", "Karta")).getId();

        ProductDto.DetailResponse updated = productService.update(id, updateRequest("SKU-T2-007", "Přejmenovaná"));

        assertThat(updated.getSku()).isEqualTo("SKU-T2-007");
        assertThat(updated.getName()).isEqualTo("Přejmenovaná");
    }

    @Test
    @DisplayName("update neexistující karty → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> productService.update(999_999L, updateRequest("SKU-X", "Cokoli")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate / activate
    // =========================================================================

    @Test
    @DisplayName("karta bez zásoby jde deaktivovat a zase aktivovat (soft-delete)")
    void deactivate_thenActivate_restoresCard() {
        Long id = productService.create(createRequest("SKU-T2-008", "Karta bez zásoby")).getId();

        ProductDto.DetailResponse deactivated = productService.deactivate(id);
        assertThat(deactivated.getActive()).isFalse();

        ProductDto.DetailResponse reactivated = productService.activate(id);
        assertThat(reactivated.getActive()).isTrue();
        assertThat(productService.getById(id).getActive()).isTrue();
    }

    @Test
    @DisplayName("deaktivovaná karta jde pořád otevřít detailem")
    void getById_deactivatedProduct_isStillReadable() {
        Long id = productService.create(createRequest("SKU-T2-009", "Karta")).getId();
        productService.deactivate(id);

        ProductDto.DetailResponse response = productService.getById(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate neexistující karty → ResourceNotFoundException (404)")
    void deactivate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> productService.deactivate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("activate neexistující karty → ResourceNotFoundException (404)")
    void activate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> productService.activate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getById
    // =========================================================================

    @Test
    @DisplayName("getById neexistující karty → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> productService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getPage / getByGoodsReceiptId
    // =========================================================================

    @Test
    @DisplayName("getPage vrátí stránku karet i s celkovým počtem")
    void getPage_returnsPagedProducts() {
        productService.create(createRequest("SKU-T2-010", "Karta A"));
        productService.create(createRequest("SKU-T2-011", "Karta B"));

        cz.palo.autoservis.model.dto.warehouse.ProductSearchParams params =
                new cz.palo.autoservis.model.dto.warehouse.ProductSearchParams();
        params.setPage(1);
        params.setPageSize(50);

        var page = productService.getPage(params);

        assertThat(page).isNotNull();
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.getContent()).extracting("sku").contains("SKU-T2-010", "SKU-T2-011");
    }

    @Test
    @DisplayName("getByGoodsReceiptId pro příjemku bez položek vrátí prázdný seznam, ne null")
    void getByGoodsReceiptId_withoutItems_returnsEmptyList() {
        assertThat(productService.getByGoodsReceiptId(999_999L))
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("getByGoodsReceiptId(null) → IllegalArgumentException (fail-fast, TD-20)")
    void getByGoodsReceiptId_nullId_throwsIllegalArgument() {
        assertThatThrownBy(() -> productService.getByGoodsReceiptId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Jednotka — kanonizace a chybové parametry
    // =========================================================================

    @Test
    @DisplayName("update převede jednotku na kanonickou podobu („KS\" → „ks\")")
    void update_canonicalizesUnit() {
        Long id = productService.create(createRequest("SKU-T2-012", "Karta")).getId();

        ProductDto.UpdateRequest request = updateRequest("SKU-T2-012", "Karta");
        request.setUnit("KS");

        ProductDto.DetailResponse updated = productService.update(id, request);

        assertThat(updated.getUnit()).isEqualTo("ks");
        assertThat(productService.getById(id).getUnit()).isEqualTo("ks");
    }

    @Test
    @DisplayName("chybějící jednotka → INVALID_UNIT s prázdným parametrem unit (ne „null\")")
    void create_nullUnit_reportsEmptyUnitParam() {
        ProductDto.CreateRequest request = createRequest("SKU-T2-013", "Karta bez jednotky");
        request.setUnit(null);

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVALID_UNIT");
                    assertThat(e.getParams()).containsEntry("unit", "");
                    assertThat(e.getParams()).containsKey("allowed");
                });
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static ProductDto.CreateRequest createRequest(String sku, String name) {
        ProductDto.CreateRequest request = new ProductDto.CreateRequest();
        request.setSku(sku);
        request.setName(name);
        request.setManufacturer("Mann");
        request.setManufacturerPartNumber("W 712/75");
        request.setUnit("ks");
        request.setDefaultVatRate(21);
        request.setSalePrice(new BigDecimal("199.00"));
        request.setMinStockLevel(new BigDecimal("5"));
        return request;
    }

    private static ProductDto.UpdateRequest updateRequest(String sku, String name) {
        ProductDto.UpdateRequest request = new ProductDto.UpdateRequest();
        request.setSku(sku);
        request.setName(name);
        request.setUnit("ks");
        request.setDefaultVatRate(21);
        request.setSalePrice(new BigDecimal("199.00"));
        request.setMinStockLevel(new BigDecimal("5"));
        return request;
    }
}
