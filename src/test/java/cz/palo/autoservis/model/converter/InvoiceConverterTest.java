package cz.palo.autoservis.model.converter;

import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceListRow;
import cz.palo.autoservis.model.domain.billing.InvoiceParty;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.domain.billing.InvoiceVatSummary;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.InvoicePartyRole;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konvertor faktur — čistý unit test bez Spring kontextu.
 *
 * <p>Nejnáchylnější místo je přiřazení <strong>zmrazených stran dokladu</strong>: seznam
 * {@code parties} obsahuje dodavatele i odběratele a rozlišují se jen podle {@code role}.
 * Kdyby se role prohodily, faktura by měla vlastní firmu jako odběratele. Proto se testuje,
 * že se každá strana dostala na správné místo — a že opačné přiřazení neplatí.
 */
class InvoiceConverterTest {

    private final InvoiceConverter converter = new InvoiceConverter();

    // =========================================================================
    // toDomain / applyUpdate
    // =========================================================================

    @Test
    @DisplayName("toDomain přenese pole CreateRequest")
    void toDomain_mapsCreateRequestFields() {
        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(5L);
        request.setIssueDate(LocalDate.of(2026, 7, 1));
        request.setDueDate(LocalDate.of(2026, 7, 15));
        request.setTaxableSupplyDate(LocalDate.of(2026, 7, 1));
        request.setConstantSymbol("0308");
        request.setSpecificSymbol("12345");
        request.setPaymentMethod(PaymentMethod.TRANSFER);
        request.setNote("splatnost 14 dní");
        request.setPurchaseOrderNumber("OBJ 2026/0455");

        Invoice result = converter.toDomain(request);

        assertThat(result.getOrderId()).isEqualTo(5L);
        assertThat(result.getIssueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(result.getTaxableSupplyDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.getConstantSymbol()).isEqualTo("0308");
        assertThat(result.getSpecificSymbol()).isEqualTo("12345");
        assertThat(result.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(result.getNote()).isEqualTo("splatnost 14 dní");
        assertThat(result.getPurchaseOrderNumber()).isEqualTo("OBJ 2026/0455");
    }

    @Test
    @DisplayName("toDomain normalizuje prázdné číslo objednávky na NULL")
    void toDomain_blankPurchaseOrderNumber_becomesNull() {
        // FE posílá nevyplněná pole jako "" — DB stojí na NULL sémantice (V80/V81).
        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(5L);
        request.setPurchaseOrderNumber("  ");

        assertThat(converter.toDomain(request).getPurchaseOrderNumber()).isNull();
    }

    @Test
    @DisplayName("toDomain nechává prázdné pole, která řídí server (číslo, VS, stav, id, audit)")
    void toDomain_leavesServerManagedFieldsEmpty() {
        // Číslo faktury ani VS koncept nemá — obojí se zapisuje až při vystavení, aby
        // zrušený koncept nespálil číslo řady. Konvertor je proto ani nesmí dosadit.
        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(5L);
        request.setIssueDate(LocalDate.of(2026, 7, 1));

        Invoice result = converter.toDomain(request);

        assertThat(result.getInvoiceNumber()).as("číslo přiděluje až vystavení").isNull();
        assertThat(result.getVariableSymbol()).as("VS se zadává až při vystavení").isNull();
        assertThat(result.getStatus()).as("výchozí DRAFT dává server/DB default").isNull();
        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedBy()).isNull();
    }

    @Test
    @DisplayName("toDomain(null) → null")
    void toDomain_null_returnsNull() {
        assertThat(converter.toDomain(null)).isNull();
    }

    @Test
    @DisplayName("applyUpdate přepíše editovatelná pole, nesahá na číslo, zakázku ani zákazníka")
    void applyUpdate_overwritesEditableFieldsOnly() {
        Invoice existing = draftInvoice();
        existing.setId(2L);
        existing.setInvoiceNumber("202607001");
        existing.setOrderId(5L);
        existing.setCustomerId(1L);
        existing.setVariableSymbol("202607001");
        existing.setIssueDate(LocalDate.of(2026, 7, 1));

        InvoiceDto.UpdateRequest request = new InvoiceDto.UpdateRequest();
        request.setDueDate(LocalDate.of(2026, 7, 31));
        request.setConstantSymbol("0558");
        request.setSpecificSymbol("999");
        request.setPaymentMethod(PaymentMethod.CASH);
        request.setStatus(InvoiceStatus.ISSUED);
        request.setNote("upraveno");
        request.setPurchaseOrderNumber("PO-77");

        Invoice result = converter.applyUpdate(existing, request);

        assertThat(result).as("mutace probíhá na místě, vrací se tentýž objekt").isSameAs(existing);
        assertThat(existing.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(existing.getConstantSymbol()).isEqualTo("0558");
        assertThat(existing.getSpecificSymbol()).isEqualTo("999");
        assertThat(existing.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(existing.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(existing.getNote()).isEqualTo("upraveno");
        assertThat(existing.getPurchaseOrderNumber()).isEqualTo("PO-77");

        assertThat(existing.getInvoiceNumber()).isEqualTo("202607001");
        assertThat(existing.getOrderId()).isEqualTo(5L);
        assertThat(existing.getCustomerId()).isEqualTo(1L);
        assertThat(existing.getVariableSymbol()).isEqualTo("202607001");
        assertThat(existing.getIssueDate()).as("datum vystavení není v UpdateRequest")
                .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("applyUpdate vrací null, chybí-li kterýkoli z argumentů")
    void applyUpdate_nullArguments_returnNull() {
        assertThat(converter.applyUpdate(null, new InvoiceDto.UpdateRequest())).isNull();
        assertThat(converter.applyUpdate(draftInvoice(), null)).isNull();
    }

    // =========================================================================
    // toDetailResponse — strany dokladu, součty, DPH rekapitulace
    // =========================================================================

    @Test
    @DisplayName("toDetailResponse přiřadí dodavatele a odběratele podle role, ne podle pořadí")
    void toDetailResponse_assignsPartiesByRole() {
        // Pořadí ve vstupu je schválně obrácené (odběratel první), aby test odhalil
        // implementaci, která by strany bral podle indexu místo podle role.
        List<InvoiceParty> parties = List.of(
                party(InvoicePartyRole.CUSTOMER, "Jan Novák", "87654321"),
                party(InvoicePartyRole.SUPPLIER, "Autoservis s.r.o.", "12345678"));

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("1000.00", "210.00", "1210.00"), parties, null, null);

        assertThat(response.getSupplier()).isNotNull();
        assertThat(response.getSupplier().getName()).isEqualTo("Autoservis s.r.o.");
        assertThat(response.getSupplier().getIco()).isEqualTo("12345678");

        assertThat(response.getCustomer()).isNotNull();
        assertThat(response.getCustomer().getName()).isEqualTo("Jan Novák");
        assertThat(response.getCustomer().getIco()).isEqualTo("87654321");
    }

    @Test
    @DisplayName("strana dokladu nese kompletní adresu i bankovní spojení (jde na tištěnou fakturu)")
    void toDetailResponse_partyCarriesFullAddressAndBankDetails() {
        List<InvoiceParty> parties = List.of(party(InvoicePartyRole.SUPPLIER, "Autoservis s.r.o.", "12345678"));

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("0", "0", "0"), parties, null, null);

        InvoiceDto.PartyResponse supplier = response.getSupplier();
        assertThat(supplier.getName()).isEqualTo("Autoservis s.r.o.");
        assertThat(supplier.getIco()).isEqualTo("12345678");
        assertThat(supplier.getDic()).isEqualTo("CZ12345678");
        assertThat(supplier.getStreet()).isEqualTo("Testovací");
        assertThat(supplier.getStreetNumber()).isEqualTo("1");
        assertThat(supplier.getCity()).isEqualTo("Praha");
        assertThat(supplier.getPostalCode()).isEqualTo("110 00");
        assertThat(supplier.getCountryCode()).isEqualTo("CZ");
        assertThat(supplier.getBankAccount()).isEqualTo("123456789/0800");
        assertThat(supplier.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(supplier.getSwift()).isEqualTo("GIBACZPX");
    }

    @Test
    @DisplayName("toDetailResponse: chybí-li seznam stran, supplier i customer zůstanou null")
    void toDetailResponse_nullParties_leavesBothSidesNull() {
        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("1000.00", "210.00", "1210.00"), null, null, null);

        assertThat(response.getSupplier()).isNull();
        assertThat(response.getCustomer()).isNull();
    }

    @Test
    @DisplayName("toDetailResponse přenese součty ze summary")
    void toDetailResponse_mapsTotals() {
        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("1000.00", "210.00", "1210.00"));

        assertThat(response.getTotalNet()).isEqualByComparingTo("1000.00");
        assertThat(response.getTotalVat()).isEqualByComparingTo("210.00");
        assertThat(response.getTotalGross()).isEqualByComparingTo("1210.00");
    }

    @Test
    @DisplayName("toDetailResponse namapuje rekapitulaci DPH po sazbách")
    void toDetailResponse_mapsVatSummaryLines() {
        List<InvoiceVatSummary> vatSummary = List.of(
                vatLine((short) 21, "1000.00", "210.00", "1210.00"),
                vatLine((short) 12, "500.00", "60.00", "560.00"));

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("1500.00", "270.00", "1770.00"), null, null, vatSummary);

        assertThat(response.getVatSummary()).hasSize(2);
        assertThat(response.getVatSummary().get(0).getRate()).isEqualTo((short) 21);
        assertThat(response.getVatSummary().get(0).getBase()).isEqualByComparingTo("1000.00");
        assertThat(response.getVatSummary().get(0).getVat()).isEqualByComparingTo("210.00");
        assertThat(response.getVatSummary().get(0).getTotal()).isEqualByComparingTo("1210.00");
        assertThat(response.getVatSummary().get(1).getRate()).isEqualTo((short) 12);
        assertThat(response.getVatSummary().get(1).getBase()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("toDetailResponse: chybějící rekapitulace DPH → prázdný seznam, ne null")
    void toDetailResponse_nullVatSummary_yieldsEmptyList() {
        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(), summary("0", "0", "0"), null, null, null);

        assertThat(response.getVatSummary()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("toDetailResponse: SPZ, VIN, značka i model ze zmraženého snapshotu, ne živě ze zakázky (K-5)")
    void toDetailResponse_vehicleDataComesFromSnapshot() {
        Invoice invoice = draftInvoice();
        invoice.setVehicleLicensePlateSnapshot("1AB 2345");
        invoice.setVehicleVinSnapshot("TMBJJ7NE0E0123456");
        invoice.setVehicleBrandSnapshot("Škoda");
        invoice.setVehicleModelSnapshot("Octavia");

        // I kdyby živá zakázka nesla jiné (změněné) hodnoty vozidla, na doklad jdou snapshoty.
        Order order = new Order();
        order.setVehicleVin("ZMENENY_VIN_NEPOUZIT");
        order.setVehicleBrand("Změněná");
        order.setVehicleModel("Změněný");

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                invoice, List.of(), summary("0", "0", "0"), null, order, null);

        assertThat(response.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(response.getVehicleVin()).as("VIN ze snapshotu, ne z živého vozidla").isEqualTo("TMBJJ7NE0E0123456");
        assertThat(response.getVehicleBrand()).isEqualTo("Škoda");
        assertThat(response.getVehicleModel()).isEqualTo("Octavia");
    }

    @Test
    @DisplayName("toDetailResponse: bez snapshotů vozidla zůstanou pole vozidla null")
    void toDetailResponse_noVehicleSnapshot_leavesVehicleFieldsNull() {
        Invoice invoice = draftInvoice();
        invoice.setVehicleLicensePlateSnapshot("1AB 2345");
        // VIN/značka/model snapshoty nenastaveny → v odpovědi null

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                invoice, List.of(), summary("0", "0", "0"), null, null, null);

        assertThat(response.getVehicleLicensePlate()).isEqualTo("1AB 2345");
        assertThat(response.getVehicleVin()).isNull();
        assertThat(response.getVehicleBrand()).isNull();
        assertThat(response.getVehicleModel()).isNull();
    }

    @Test
    @DisplayName("toDetailResponse(null faktura) → null")
    void toDetailResponse_nullInvoice_returnsNull() {
        assertThat(converter.toDetailResponse(null)).isNull();
        assertThat(converter.toDetailResponse(null, List.of(), summary("0", "0", "0"))).isNull();
    }

    @Test
    @DisplayName("toDetailResponse bez položek namapuje hlavičku včetně snapshotů")
    void toDetailResponse_headerOnly_mapsSnapshots() {
        Invoice invoice = draftInvoice();
        invoice.setId(2L);
        invoice.setInvoiceNumber("202607001");
        invoice.setOrderId(5L);
        invoice.setCustomerId(1L);
        invoice.setTaxableSupplyDate(LocalDate.of(2026, 7, 1));
        invoice.setVariableSymbol("202607001");
        invoice.setConstantSymbol("0308");
        invoice.setSpecificSymbol("12345");
        invoice.setNote("splatnost 14 dní");
        invoice.setCustomerNameSnapshot("Jan Novák");
        invoice.setOrderNumberSnapshot("ZAK-2026-0001");
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setCreatedBy(9L);
        invoice.setCreatedAt(java.time.OffsetDateTime.parse("2026-07-01T09:00:00Z"));
        invoice.setUpdatedAt(java.time.OffsetDateTime.parse("2026-07-02T09:00:00Z"));

        InvoiceDto.DetailResponse response = converter.toDetailResponse(invoice);

        assertThat(response.getId()).isEqualTo(2L);
        assertThat(response.getInvoiceNumber()).isEqualTo("202607001");
        assertThat(response.getOrderId()).isEqualTo(5L);
        assertThat(response.getCustomerId()).isEqualTo(1L);
        assertThat(response.getIssueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(response.getTaxableSupplyDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.getVariableSymbol()).isEqualTo("202607001");
        assertThat(response.getConstantSymbol()).isEqualTo("0308");
        assertThat(response.getSpecificSymbol()).isEqualTo("12345");
        assertThat(response.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(response.getNote()).isEqualTo("splatnost 14 dní");
        assertThat(response.getCustomerNameSnapshot()).isEqualTo("Jan Novák");
        assertThat(response.getOrderNumberSnapshot()).isEqualTo("ZAK-2026-0001");
        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(response.getCreatedBy()).isEqualTo(9L);
        assertThat(response.getCreatedAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-07-01T09:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-07-02T09:00:00Z"));
        assertThat(response.getItems()).as("varianta bez položek je nevyplňuje").isNull();
    }

    @Test
    @DisplayName("toDetailResponse připojí předané položky faktury")
    void toDetailResponse_attachesItems() {
        cz.palo.autoservis.model.dto.billing.InvoiceItemDto.Response item =
                new cz.palo.autoservis.model.dto.billing.InvoiceItemDto.Response();
        item.setId(4L);
        item.setName("Olejový filtr");

        InvoiceDto.DetailResponse response = converter.toDetailResponse(
                draftInvoice(), List.of(item), summary("1000.00", "210.00", "1210.00"));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().getFirst().getId()).isEqualTo(4L);
        assertThat(response.getItems().getFirst().getName()).isEqualTo("Olejový filtr");
    }

    // =========================================================================
    // toListResponses
    // =========================================================================

    @Test
    @DisplayName("toListResponses namapuje read-model řádky a zachová pořadí")
    void toListResponses_mapsRowsInOrder() {
        InvoiceListRow issued = InvoiceListRow.builder()
                .id(2L)
                .invoiceNumber("202607001")
                .issueDate(LocalDate.of(2026, 7, 1))
                .dueDate(LocalDate.of(2026, 7, 15))
                .status(InvoiceStatus.ISSUED)
                .paymentMethod(PaymentMethod.TRANSFER)
                .variableSymbol("202607001")
                .orderId(5L)
                .orderNumber("ZAK-2026-0001")
                .customerId(1L)
                .customerDisplayName("Jan Novák")
                .totalNet(new BigDecimal("1000.00"))
                .totalVat(new BigDecimal("210.00"))
                .totalGross(new BigDecimal("1210.00"))
                .build();

        InvoiceListRow paid = InvoiceListRow.builder()
                .id(3L)
                .invoiceNumber("202607002")
                .status(InvoiceStatus.PAID)
                .customerDisplayName("Autodíly s.r.o.")
                .totalGross(new BigDecimal("500.00"))
                .build();

        List<InvoiceDto.ListResponse> result = converter.toListResponses(List.of(issued, paid));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(2L);
        assertThat(result.get(0).getInvoiceNumber()).isEqualTo("202607001");
        assertThat(result.get(0).getIssueDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(result.get(0).getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(result.get(0).getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(result.get(0).getVariableSymbol()).isEqualTo("202607001");
        assertThat(result.get(0).getOrderId()).isEqualTo(5L);
        assertThat(result.get(0).getOrderNumber()).isEqualTo("ZAK-2026-0001");
        assertThat(result.get(0).getCustomerId()).isEqualTo(1L);
        assertThat(result.get(0).getCustomerDisplayName()).isEqualTo("Jan Novák");
        assertThat(result.get(0).getTotalNet()).isEqualByComparingTo("1000.00");
        assertThat(result.get(0).getTotalVat()).isEqualByComparingTo("210.00");
        assertThat(result.get(0).getTotalGross()).isEqualByComparingTo("1210.00");

        assertThat(result.get(1).getId()).isEqualTo(3L);
        assertThat(result.get(1).getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(result.get(1).getCustomerDisplayName()).isEqualTo("Autodíly s.r.o.");
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private static Invoice draftInvoice() {
        Invoice invoice = new Invoice();
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setIssueDate(LocalDate.of(2026, 7, 1));
        invoice.setDueDate(LocalDate.of(2026, 7, 15));
        invoice.setPaymentMethod(PaymentMethod.TRANSFER);
        return invoice;
    }

    private static InvoiceSummary summary(String net, String vat, String gross) {
        return InvoiceSummary.builder()
                .invoiceId(2L)
                .totalNet(new BigDecimal(net))
                .totalVat(new BigDecimal(vat))
                .totalGross(new BigDecimal(gross))
                .build();
    }

    private static InvoiceVatSummary vatLine(short rate, String base, String vat, String total) {
        return InvoiceVatSummary.builder()
                .invoiceId(2L)
                .vatRate(rate)
                .base(new BigDecimal(base))
                .vat(new BigDecimal(vat))
                .total(new BigDecimal(total))
                .build();
    }

    private static InvoiceParty party(InvoicePartyRole role, String name, String ico) {
        return InvoiceParty.builder()
                .invoiceId(2L)
                .role(role)
                .name(name)
                .ico(ico)
                .dic("CZ" + ico)
                .street("Testovací")
                .streetNumber("1")
                .city("Praha")
                .postalCode("110 00")
                .countryCode("CZ")
                .bankAccount("123456789/0800")
                .iban("CZ6508000000192000145399")
                .swift("GIBACZPX")
                .build();
    }
}
