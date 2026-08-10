package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.SupplierMapper;
import cz.palo.autoservis.model.domain.warehouse.Supplier;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dodavatelé ({@code SupplierServiceImpl}) — RUD bez Create (dodavatel vzniká výhradně
 * importem dokladu, proto si fixturu zakládá test sám přes mapper).
 *
 * <p>Zvláštní pozornost: registrační číslo (IČO) se před uložením i před kontrolou duplicity
 * <strong>normalizuje</strong>. Kdyby normalizace vypadla, „123 456 78" a „12345678" by byli
 * dva různí dodavatelé a párování při importu by přestalo fungovat.
 */
@Transactional
class SupplierServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private cz.palo.autoservis.mapper.WarehouseImportMapper warehouseImportMapper;

    private Long supplierId;
    private Long otherSupplierId;

    @BeforeEach
    void createSuppliers() {
        supplierId = insertSupplier("Autodíly s.r.o.", "12345678");
        otherSupplierId = insertSupplier("Náhradní díly a.s.", "87654321");
    }

    // =========================================================================
    // getById
    // =========================================================================

    @Test
    @DisplayName("getById vrátí dodavatele se všemi údaji")
    void getById_returnsSupplier() {
        SupplierDto.DetailResponse response = supplierService.getById(supplierId);

        assertThat(response.getId()).isEqualTo(supplierId);
        assertThat(response.getName()).isEqualTo("Autodíly s.r.o.");
        assertThat(response.getRegistrationNumber()).isEqualTo("12345678");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("getById neexistujícího dodavatele → ResourceNotFoundException (404)")
    void getById_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> supplierService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // getPage
    // =========================================================================

    @Test
    @DisplayName("getPage vrátí stránku dodavatelů i s celkovým počtem")
    void getPage_returnsPagedSuppliers() {
        cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams params =
                new cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams();
        params.setPage(1);
        params.setPageSize(10);

        var page = supplierService.getPage(params);

        assertThat(page).isNotNull();
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.getContent()).extracting("name")
                .contains("Autodíly s.r.o.", "Náhradní díly a.s.");
    }

    @Test
    @DisplayName("getPage s filtrem vrátí jen odpovídajícího dodavatele")
    void getPage_withSearchFilter_narrowsResult() {
        cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams params =
                new cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setSearch("Náhradní");

        var page = supplierService.getPage(params);

        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getContent()).extracting("name").contains("Náhradní díly a.s.");
        assertThat(page.getContent()).extracting("name")
                .doesNotContain("Autodíly s.r.o.");
    }

    // =========================================================================
    // update
    // =========================================================================

    @Test
    @DisplayName("update přepíše údaje dodavatele a vrátí čerstvý stav z DB")
    void update_overwritesFields() {
        SupplierDto.UpdateRequest request = updateRequest("Autodíly a.s.", "12345678");
        request.setCity("Praha");
        request.setEmail("novy@autodily.cz");
        request.setIban("CZ6508000000192000145399");

        SupplierDto.DetailResponse updated = supplierService.update(supplierId, request);

        assertThat(updated.getName()).isEqualTo("Autodíly a.s.");
        assertThat(updated.getCity()).isEqualTo("Praha");
        assertThat(updated.getEmail()).isEqualTo("novy@autodily.cz");
        assertThat(updated.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(supplierMapper.findById(supplierId).orElseThrow().getName()).isEqualTo("Autodíly a.s.");
    }

    @Test
    @DisplayName("update normalizuje IČO — mezery se odstraní ještě před uložením")
    void update_normalizesRegistrationNumber() {
        SupplierDto.DetailResponse updated =
                supplierService.update(supplierId, updateRequest("Autodíly s.r.o.", "123 456 78"));

        assertThat(updated.getRegistrationNumber()).isEqualTo("12345678");
        assertThat(supplierMapper.findById(supplierId).orElseThrow().getRegistrationNumber())
                .isEqualTo("12345678");
    }

    @Test
    @DisplayName("update na IČO jiného dodavatele → DUPLICATE_REGISTRATION_NUMBER (422)")
    void update_registrationNumberTakenByAnother_throwsBusinessRule() {
        assertThatThrownBy(() -> supplierService.update(
                supplierId, updateRequest("Autodíly s.r.o.", "87654321")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("DUPLICATE_REGISTRATION_NUMBER");
                    assertThat(e.getField()).isEqualTo("registrationNumber");
                });
    }

    @Test
    @DisplayName("kontrola duplicity běží až po normalizaci — „876 543 21\" koliduje stejně jako „87654321\"")
    void update_duplicateDetectedAfterNormalization() {
        assertThatThrownBy(() -> supplierService.update(
                supplierId, updateRequest("Autodíly s.r.o.", "876 543 21")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_REGISTRATION_NUMBER"));
    }

    @Test
    @DisplayName("update ponechávající vlastní IČO projde (nekoliduje sám se sebou)")
    void update_keepingOwnRegistrationNumber_succeeds() {
        SupplierDto.DetailResponse updated =
                supplierService.update(supplierId, updateRequest("Přejmenovaný dodavatel", "12345678"));

        assertThat(updated.getName()).isEqualTo("Přejmenovaný dodavatel");
        assertThat(updated.getRegistrationNumber()).isEqualTo("12345678");
    }

    @Test
    @DisplayName("prázdné IČO uloženou hodnotu VYMAŽE — full-replace sémantika (TD-54)")
    void update_blankRegistrationNumber_clearsStoredValue() {
        // Normalizace udělá z "   " null a full-replace ho zapíše → IČO se vymaže.
        // Sladěno s ostatními moduly (dřív PATCH nechával starou hodnotu).
        SupplierDto.DetailResponse updated =
                supplierService.update(supplierId, updateRequest("Autodíly s.r.o.", "   "));

        assertThat(updated.getRegistrationNumber()).isNull();
        assertThat(supplierMapper.findById(supplierId).orElseThrow().getRegistrationNumber())
                .as("null je i v DB, ne jen v odpovědi").isNull();
    }

    @Test
    @DisplayName("full-replace vymaže i další volitelná pole (IBAN, e-mail), když je request neuvádí")
    void update_omittedOptionalFields_areCleared() {
        // nejdřív dodavatele obohatíme
        SupplierDto.UpdateRequest full = updateRequest("Autodíly s.r.o.", "12345678");
        full.setIban("CZ6508000000192000145399");
        full.setEmail("obchod@autodily.cz");
        supplierService.update(supplierId, full);
        assertThat(supplierMapper.findById(supplierId).orElseThrow().getIban()).isNotNull();

        // update, který IBAN a e-mail neuvádí (null) → full-replace je vynuluje
        supplierService.update(supplierId, updateRequest("Autodíly s.r.o.", "12345678"));

        Supplier reloaded = supplierMapper.findById(supplierId).orElseThrow();
        assertThat(reloaded.getIban()).as("neuvedený IBAN se vymaže").isNull();
        assertThat(reloaded.getEmail()).as("neuvedený e-mail se vymaže").isNull();
        assertThat(reloaded.getName()).as("povinné jméno zůstává").isEqualTo("Autodíly s.r.o.");
    }

    @Test
    @DisplayName("prázdné IČO přeskočí kontrolu duplicity (null nekoliduje) a uloží se jako null")
    void update_blankRegistrationNumber_skipsDuplicateCheckAndClears() {
        SupplierDto.DetailResponse updated =
                supplierService.update(supplierId, updateRequest("Přejmenovaný", null));

        assertThat(updated.getName()).isEqualTo("Přejmenovaný");
        assertThat(updated.getRegistrationNumber()).isNull();
    }

    @Test
    @DisplayName("chybějící countryCode se NEvymaže — je NOT NULL, ponechá se (COALESCE)")
    void update_omittedCountryCode_isKept() {
        // country_code je NOT NULL DEFAULT 'CZ' — na rozdíl od ostatních polí ho full-replace
        // nevynuluje (jinak by spadl na constraint). updateRequest countryCode nenastavuje.
        supplierService.update(supplierId, updateRequest("Autodíly s.r.o.", "12345678"));

        assertThat(supplierMapper.findById(supplierId).orElseThrow().getCountryCode())
                .as("původní CZ zůstává").isEqualTo("CZ");
    }

    @Test
    @DisplayName("update neexistujícího dodavatele → ResourceNotFoundException (404)")
    void update_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> supplierService.update(999_999L, updateRequest("Kdokoli", "11111111")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // deactivate / activate
    // =========================================================================

    @Test
    @DisplayName("deactivate dodavatele je vratná přes activate (soft-delete)")
    void deactivate_thenActivate_restoresSupplier() {
        SupplierDto.DetailResponse deactivated = supplierService.deactivate(supplierId);
        assertThat(deactivated.isActive()).isFalse();
        assertThat(supplierMapper.findById(supplierId).orElseThrow().isActive()).isFalse();

        SupplierDto.DetailResponse reactivated = supplierService.activate(supplierId);
        assertThat(reactivated.isActive()).isTrue();
        assertThat(supplierMapper.findById(supplierId).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("deaktivovaného dodavatele lze pořád načíst detailem (permissive findById)")
    void getById_deactivatedSupplier_isStillReadable() {
        supplierService.deactivate(supplierId);

        SupplierDto.DetailResponse response = supplierService.getById(supplierId);

        assertThat(response.getId()).isEqualTo(supplierId);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate neexistujícího dodavatele → ResourceNotFoundException (404)")
    void deactivate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> supplierService.deactivate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("activate neexistujícího dodavatele → ResourceNotFoundException (404)")
    void activate_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> supplierService.activate(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deaktivace jednoho dodavatele se nedotkne ostatních")
    void deactivate_doesNotAffectOtherSuppliers() {
        supplierService.deactivate(supplierId);

        assertThat(supplierService.getById(otherSupplierId).isActive()).isTrue();
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private Long insertSupplier(String name, String registrationNumber) {
        Supplier supplier = Supplier.builder()
                .name(name)
                .registrationNumber(registrationNumber)
                .vatId("CZ" + registrationNumber)
                .street("Skladová 7")
                .city("Brno")
                .postalCode("602 00")
                .countryCode("CZ")
                .active(true)
                .build();
        // SupplierMapper úmyslně nemá insert — dodavatel v aplikaci vzniká výhradně
        // importem dokladu, takže fixtura jde stejnou cestou jako produkční kód.
        warehouseImportMapper.insertSupplier(supplier);
        return supplier.getId();
    }

    private static SupplierDto.UpdateRequest updateRequest(String name, String registrationNumber) {
        SupplierDto.UpdateRequest request = new SupplierDto.UpdateRequest();
        request.setName(name);
        request.setRegistrationNumber(registrationNumber);
        return request;
    }
}
