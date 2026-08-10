package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Z-4: create/update skladové karty odmítne jednotku mimo číselník
 * ({@code INVALID_UNIT}, 422) a platnou jednotku uloží kanonicky („KS" → „ks").
 */
@Transactional
class ProductUnitValidationTest extends AbstractIntegrationTest {

    @Autowired
    private ProductService productService;

    private ProductDto.CreateRequest createRequest(String sku, String unit) {
        return ProductDto.CreateRequest.builder()
                .sku(sku).name("Testovací díl").unit(unit).defaultVatRate(21)
                .build();
    }

    @Test
    @DisplayName("create s jednotkou mimo číselník → BusinessRuleException INVALID_UNIT")
    void createRejectsInvalidUnit() {
        var ex = catchThrowableOfType(
                () -> productService.create(createRequest("UNIT-BAD-1", "krabice")),
                BusinessRuleException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getRuleCode()).isEqualTo("INVALID_UNIT");
    }

    @Test
    @DisplayName("create s platnou jednotkou (jiná velikost písmen) ji uloží kanonicky")
    void createCanonicalizesValidUnit() {
        var detail = productService.create(createRequest("UNIT-OK-1", "KS"));
        assertThat(detail.getUnit()).isEqualTo("ks");
    }

    @Test
    @DisplayName("update na jednotku mimo číselník → INVALID_UNIT")
    void updateRejectsInvalidUnit() {
        var created = productService.create(createRequest("UNIT-UPD-1", "ks"));
        var update = ProductDto.UpdateRequest.builder()
                .sku("UNIT-UPD-1").name("Testovací díl").unit("kus").defaultVatRate(21)
                .build();
        assertThatThrownBy(() -> productService.update(created.getId(), update))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("ruleCode", "INVALID_UNIT");
    }
}
