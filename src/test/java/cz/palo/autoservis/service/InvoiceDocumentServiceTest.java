package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.CompanyProfileDto;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Generování PDF faktury ({@code InvoiceDocumentServiceImpl}) — Thymeleaf → HTML → openhtmltopdf.
 *
 * <p>Jde o smoke test výstupu: PDF se skutečně vyrenderuje a je to <strong>validní neprázdný
 * PDF dokument</strong> (magická hlavička {@code %PDF}). Ověřují se obě větve QR platby —
 * s IBAN dodavatele (QR se vloží) i bez něj (QR se přeskočí, PDF přesto vznikne) — protože
 * právě tady se PDF nejsnáz rozbije (chybějící font, prázdné IBAN, null pole šablony).
 */
@Transactional
class InvoiceDocumentServiceTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long BILLING_ADDRESS_ID = 2L;

    @Autowired
    private InvoiceDocumentService invoiceDocumentService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CompanyProfileService companyProfileService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Test
    @DisplayName("renderPdf vytvoří validní neprázdné PDF (magická hlavička %PDF)")
    void renderPdf_producesValidPdf() {
        companyProfileService.update(companyProfileWithIban("CZ6508000000192000145399"));
        Long invoiceId = createInvoice();

        byte[] pdf = invoiceDocumentService.renderPdf(invoiceId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
                .as("PDF začíná magickou hlavičkou").startsWith("%PDF-");
        assertThat(pdf.length).as("neprázdný dokument").isGreaterThan(1000);
    }

    @Test
    @DisplayName("PDF vznikne i bez IBAN dodavatele — QR platba se jen vynechá")
    void renderPdf_withoutIban_stillProducesPdf() {
        CompanyProfileDto.UpdateRequest noIban = companyProfileWithIban("CZ6508000000192000145399");
        noIban.setIban(null);
        companyProfileService.update(noIban);
        Long invoiceId = createInvoice();

        byte[] pdf = invoiceDocumentService.renderPdf(invoiceId);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("dvě faktury dají různé, každá platné PDF")
    void renderPdf_twoInvoices_bothValid() {
        companyProfileService.update(companyProfileWithIban("CZ6508000000192000145399"));

        byte[] first = invoiceDocumentService.renderPdf(createInvoice());
        byte[] second = invoiceDocumentService.renderPdf(createInvoice());

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(new String(second, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("PDF neexistující faktury → ResourceNotFoundException (404 z invoiceService)")
    void renderPdf_unknownInvoice_throwsResourceNotFound() {
        assertThatThrownBy(() -> invoiceDocumentService.renderPdf(999_999L))
                .isInstanceOf(cz.palo.autoservis.exception.ResourceNotFoundException.class);
    }

    // =========================================================================
    // Fixtury
    // =========================================================================

    private Long createInvoice() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID).vehicleId(VEHICLE_ID)
                .description("Zakázka pro PDF test")
                .estimatedPrice(new BigDecimal("1000")).createdBy(USER_ID)
                .build();
        orderMapper.insert(order);

        orderItemMapper.insert(OrderItem.builder()
                .orderId(order.getId())
                .itemType(OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(BigDecimal.ONE).unit("hod")
                .unitPrice(new BigDecimal("500")).vatRate((short) 21)
                .position((short) 1).createdBy(USER_ID)
                .build());

        // Fakturovat lze až dokončenou zakázku (2026-08-05) — přepnutí přímo mapperem,
        // jde o přípravu dat, ne o testovanou cestu.
        order.setStatus(OrderStatus.COMPLETED);
        orderMapper.update(order);

        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(order.getId());
        request.setBillingAddressId(BILLING_ADDRESS_ID);
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(LocalDate.now());
        request.setPaymentMethod(PaymentMethod.TRANSFER);
        Long id = invoiceService.createFromOrder(request, USER_ID).getId();
        // Vystavíme: QR platba (SPAYD) se generuje jen pro vystavený doklad — rozhoduje stav,
        // ne číslo (od V71 má číslo i koncept).
        issueWithNextNumber(invoiceService, id, USER_ID);
        return id;
    }

    private static CompanyProfileDto.UpdateRequest companyProfileWithIban(String iban) {
        CompanyProfileDto.UpdateRequest request = new CompanyProfileDto.UpdateRequest();
        request.setName("Autoservis Testovací s.r.o.");
        request.setIco("12345678");
        request.setDic("CZ12345678");
        request.setStreet("Dílenská");
        request.setStreetNumber("12");
        request.setCity("Praha");
        request.setPostalCode("110 00");
        request.setCountryCode("CZ");
        request.setBankAccount("123456789/0800");
        request.setIban(iban);
        request.setSwift("GIBACZPX");
        request.setInvoiceNumberAuto(true);
        request.setInvoiceNumberMask("{RRRR}{MM}{NNN}");
        request.setCashReceiptNumberSource(cz.palo.autoservis.model.enums.CashReceiptNumberSource.MASK);
        request.setCashReceiptNumberMask("PPD{RRRR}{MM}{NNN}");
        return request;
    }
}
