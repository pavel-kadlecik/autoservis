package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.vehicle.MileageDto;
import cz.palo.autoservis.model.enums.MileageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zakázkový list (audit KN-28) — snímek tachometru při příjmu + render PDF.
 *
 * <p>PDF se ověřuje jako smoke test (validní neprázdný {@code %PDF}), stejně jako u faktury:
 * šablona se nejsnáz rozbije na chybějícím fontu nebo na {@code null} poli, ne na obsahu.
 * Zvlášť se proto testuje i zakázka <strong>bez</strong> vyplněného tachometru a bez odhadu ceny —
 * u vozu, který přijede odtažený, je to běžný stav a doklad musí vzniknout i tak.
 *
 * <p>Druhá polovina testu je datová: km z příjmu se ukládají na zakázku (snímek dokladu)
 * a zároveň zakládají odečet v historii vozidla (audit 07/P-14) — ale jen při zakládání zakázky.
 */
@Transactional
class OrderProtocolDocumentTest extends AbstractIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long CUSTOMER_ID = 1L;
    private static final Long VEHICLE_ID = 1L;

    @Autowired
    private OrderDocumentService orderDocumentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MileageService mileageService;

    // =========================================================================
    // Tachometr při příjmu
    // =========================================================================

    @Test
    @DisplayName("km z příjmu se uloží na zakázku a zároveň založí odečet v historii vozidla")
    void createWithIntakeMileage_storesSnapshotAndVehicleReading() {
        int readingsBefore = mileageService.findByVehicleId(VEHICLE_ID).size();

        OrderDto.DetailResponse order = orderService.create(createRequest(190_000), USER_ID);

        assertThat(order.getMileageKmAtIntake()).isEqualTo(190_000);

        List<MileageDto.Response> readings = mileageService.findByVehicleId(VEHICLE_ID);
        assertThat(readings).hasSize(readingsBefore + 1);
        assertThat(readings)
                .anySatisfy(reading -> {
                    assertThat(reading.getMileageKm()).isEqualTo(190_000);
                    assertThat(reading.getSource()).isEqualTo(MileageSource.SERVICE);
                    assertThat(reading.getNote())
                            .as("v historii vozu musí být vidět, ze které zakázky odečet přišel")
                            .contains(order.getOrderNumber());
                });
    }

    @Test
    @DisplayName("zakázka bez tachometru odečet nezakládá (odtažený vůz je běžný případ)")
    void createWithoutIntakeMileage_addsNoReading() {
        int readingsBefore = mileageService.findByVehicleId(VEHICLE_ID).size();

        OrderDto.DetailResponse order = orderService.create(createRequest(null), USER_ID);

        assertThat(order.getMileageKmAtIntake()).isNull();
        assertThat(mileageService.findByVehicleId(VEHICLE_ID)).hasSize(readingsBefore);
    }

    @Test
    @DisplayName("dodatečné dopsání km přes editaci uloží snímek, ale odečet už nezakládá")
    void updateWithIntakeMileage_storesSnapshotWithoutNewReading() {
        OrderDto.DetailResponse order = orderService.create(createRequest(null), USER_ID);
        int readingsBefore = mileageService.findByVehicleId(VEHICLE_ID).size();

        OrderDto.UpdateRequest update = new OrderDto.UpdateRequest();
        update.setStatus(order.getStatus());
        update.setDescription(order.getDescription());
        update.setMileageKmAtIntake(123_456);
        update.setReceivedAt(order.getReceivedAt());

        OrderDto.DetailResponse updated = orderService.update(order.getId(), update, USER_ID);

        assertThat(updated.getMileageKmAtIntake()).isEqualTo(123_456);
        assertThat(mileageService.findByVehicleId(VEHICLE_ID))
                .as("editace nesmí sypat do historie vozu duplicitní odečty")
                .hasSize(readingsBefore);
    }

    // =========================================================================
    // PDF
    // =========================================================================

    @Test
    @DisplayName("renderPdf vytvoří validní neprázdné PDF zakázkového listu")
    void renderPdf_producesValidPdf() {
        Long orderId = orderService.create(createRequest(190_000), USER_ID).getId();

        byte[] pdf = orderDocumentService.renderPdf(orderId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
                .as("PDF začíná magickou hlavičkou").startsWith("%PDF-");
        assertThat(pdf.length).as("neprázdný dokument").isGreaterThan(1000);
    }

    @Test
    @DisplayName("PDF vznikne i bez tachometru a bez odhadu ceny (prázdná pole šablonu neshodí)")
    void renderPdf_withoutMileageAndEstimate_stillProducesPdf() {
        OrderDto.CreateRequest request = createRequest(null);
        request.setEstimatedPrice(null);
        request.setEstimatedCompletionAt(null);
        Long orderId = orderService.create(request, USER_ID).getId();

        byte[] pdf = orderDocumentService.renderPdf(orderId);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    // =========================================================================
    // Privátní pomocníci
    // =========================================================================

    /** Zakázka pro seed zákazníka 1 / vozidlo 1 (BMW, patří zákazníkovi 1). */
    private OrderDto.CreateRequest createRequest(Integer mileageKmAtIntake) {
        OrderDto.CreateRequest request = new OrderDto.CreateRequest();
        request.setReceivedAt(LocalDate.now());
        request.setCustomerId(CUSTOMER_ID);
        request.setVehicleId(VEHICLE_ID);
        request.setDescription("KN-28 test — výměna rozvodů a vodní pumpy");
        request.setEstimatedPrice(new BigDecimal("18500.00"));
        request.setMileageKmAtIntake(mileageKmAtIntake);
        return request;
    }
}
