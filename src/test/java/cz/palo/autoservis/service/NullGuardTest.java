package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TD-20 (plan-oprav.md D2) — namátkově ověřuje, že null guardy doplněné do service
 * vrstvy napříč {@code VehicleServiceImpl}, {@code OrderServiceImpl}, {@code OrderItemServiceImpl},
 * {@code InvoiceServiceImpl}, {@code ProductServiceImpl}, {@code SupplierServiceImpl},
 * {@code MileageServiceImpl}, {@code UserServiceImpl} a {@code ReceiptReviewServiceImpl}
 * skutečně vyhodí {@link IllegalArgumentException} pro {@code null} identifikátor,
 * místo aby ho pustily až do mapperu (kde by buď spadl na NPE, nebo tiše vrátil
 * prázdný/chybějící výsledek).
 *
 * <p>{@code CustomerServiceImpl} a {@code GoodsReceiptServiceImpl} tento guard měly už
 * před D2 (viz {@code CustomerServiceTest.GetById.nullId_throwsIllegalArgumentException})
 * — zde se neopakují.
 *
 * <p>Jde o namátkovou kontrolu, ne o úplný výčet: jedna reprezentativní metoda na každou
 * service třídu stačí k důkazu, že pattern je přes Spring zapojen správně; úplný seznam
 * hlídaných metod je zdokumentován v Javadocu jednotlivých implementací service.
 */
@Transactional
class NullGuardTest extends AbstractIntegrationTest {

    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private OrderItemService orderItemService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private ProductService productService;
    @Autowired private SupplierService supplierService;
    @Autowired private MileageService mileageService;
    @Autowired private UserService userService;
    @Autowired private ReceiptReviewService receiptReviewService;

    @Test
    @DisplayName("VehicleService.getById(null) → IllegalArgumentException")
    void vehicleService_getById_null() {
        assertThatThrownBy(() -> vehicleService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OrderService.getById(null) → IllegalArgumentException")
    void orderService_getById_null() {
        assertThatThrownBy(() -> orderService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OrderItemService.getById(null) → IllegalArgumentException")
    void orderItemService_getById_null() {
        assertThatThrownBy(() -> orderItemService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("InvoiceService.getById(null) → IllegalArgumentException")
    void invoiceService_getById_null() {
        assertThatThrownBy(() -> invoiceService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ProductService.getById(null) → IllegalArgumentException")
    void productService_getById_null() {
        assertThatThrownBy(() -> productService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SupplierService.getById(null) → IllegalArgumentException")
    void supplierService_getById_null() {
        assertThatThrownBy(() -> supplierService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MileageService.findByVehicleId(null) → IllegalArgumentException")
    void mileageService_findByVehicleId_null() {
        assertThatThrownBy(() -> mileageService.findByVehicleId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UserService.getById(null) → IllegalArgumentException")
    void userService_getById_null() {
        assertThatThrownBy(() -> userService.getById(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReceiptReviewService.getDetail(null) → IllegalArgumentException")
    void receiptReviewService_getDetail_null() {
        assertThatThrownBy(() -> receiptReviewService.getDetail(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
