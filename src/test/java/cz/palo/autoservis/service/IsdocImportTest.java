package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E7 (P-6): ISDOC adaptér — český standard e-faktury do téhož kanonického draftu.
 *
 * <p>Fixtury odpovídají <b>oficiálnímu XSD ISDOC 6.0.2</b> (isdoc.cz), ne zjednodušenému
 * vzorku ve složce {@code import/} — ten je syntetický a postrádá povinné elementy.
 */
@AutoConfigureMockMvc
@Transactional
class IsdocImportTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/warehouse/receipts/import-isdoc";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IsdocParser parser;

    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L).username("admin").passwordHash("n/a")
                .enabled(true).accountNonExpired(true).accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    /** Konzistentní ISDOC faktura: 2 ks × 500 Kč, 21 % → 1000 / 210 / 1210. */
    private String invoiceXml(String documentType, String documentNumber) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="http://isdoc.cz/namespace/2013" version="6.0.2">
                <DocumentType>%s</DocumentType>
                <ID>%s</ID>
                <IssueDate>2026-07-01</IssueDate>
                <TaxPointDate>2026-07-01</TaxPointDate>
                <DueDate>2026-07-15</DueDate>
                <LocalCurrencyCode>CZK</LocalCurrencyCode>
                <CurrencyCode>CZK</CurrencyCode>
                <AccountingSupplierParty>
                    <Party>
                        <PartyIdentification><ID>24787426</ID></PartyIdentification>
                        <PartyName><Name>ISDOC dodavatel s.r.o.</Name></PartyName>
                        <PostalAddress>
                            <StreetName>Testovací</StreetName>
                            <BuildingNumber>7</BuildingNumber>
                            <CityName>Praha</CityName>
                            <PostalZone>11000</PostalZone>
                        </PostalAddress>
                        <PartyTaxScheme><CompanyID>CZ24787426</CompanyID></PartyTaxScheme>
                    </Party>
                </AccountingSupplierParty>
                <InvoiceLines>
                    <InvoiceLine>
                        <ID>1</ID>
                        <InvoicedQuantity unitCode="C62">2</InvoicedQuantity>
                        <LineExtensionAmount>1000.00</LineExtensionAmount>
                        <LineExtensionAmountTaxInclusive>1210.00</LineExtensionAmountTaxInclusive>
                        <LineExtensionTaxAmount>210.00</LineExtensionTaxAmount>
                        <UnitPrice>500.00</UnitPrice>
                        <ClassifiedTaxCategory><Percent>21</Percent></ClassifiedTaxCategory>
                        <Item>
                            <Description>Brzdové destičky přední</Description>
                            <SellersItemIdentification><ID>ISD-100</ID></SellersItemIdentification>
                        </Item>
                    </InvoiceLine>
                </InvoiceLines>
                <TaxTotal>
                    <TaxSubTotal>
                        <TaxableAmount>1000.00</TaxableAmount>
                        <TaxAmount>210.00</TaxAmount>
                        <TaxInclusiveAmount>1210.00</TaxInclusiveAmount>
                        <TaxCategory><Percent>21</Percent></TaxCategory>
                    </TaxSubTotal>
                    <TaxAmount>210.00</TaxAmount>
                </TaxTotal>
                <LegalMonetaryTotal>
                    <TaxExclusiveAmount>1000.00</TaxExclusiveAmount>
                    <TaxInclusiveAmount>1210.00</TaxInclusiveAmount>
                    <PayableAmount>1210.00</PayableAmount>
                </LegalMonetaryTotal>
            </Invoice>
            """.formatted(documentType, documentNumber);
    }

    private MockMultipartFile file(String xml) {
        return new MockMultipartFile("file", "faktura.isdoc", "application/xml",
                xml.getBytes(StandardCharsets.UTF_8));
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("parser mapuje hlavičku, dodavatele i řádek — vše VERBATIM")
    void parserMapsAllFieldsVerbatim() {
        var draft = parser.parse(invoiceXml("1", "ISD-2026-1").getBytes(StandardCharsets.UTF_8));

        var header = draft.getHeader();
        assertThat(header.getDocumentNumber().getValue()).isEqualTo("ISD-2026-1");
        assertThat(header.getDocumentNumber().getState())
                .isEqualTo(cz.palo.autoservis.model.draft.FieldState.VERBATIM);
        assertThat(header.getIssueDate().getValue()).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(header.getDueDate().getValue()).isEqualTo(java.time.LocalDate.of(2026, 7, 15));
        assertThat(header.getCurrency().getValue()).isEqualTo("CZK");
        assertThat(header.getSubtotal().getValue()).isEqualByComparingTo("1000.00");
        assertThat(header.getVatAmount().getValue()).isEqualByComparingTo("210.00");
        assertThat(header.getTotalAmount().getValue()).isEqualByComparingTo("1210.00");

        var supplier = draft.getSupplier().getExtracted();
        assertThat(supplier.getName()).isEqualTo("ISDOC dodavatel s.r.o.");
        assertThat(supplier.getRegistrationNumber()).isEqualTo("24787426");
        assertThat(supplier.getVatId()).isEqualTo("CZ24787426");
        assertThat(supplier.getStreet()).isEqualTo("Testovací 7");
        assertThat(supplier.getCity()).isEqualTo("Praha");

        assertThat(draft.getLines()).hasSize(1);
        var line = draft.getLines().get(0);
        // katalogové číslo dodavatele — na něm stojí párovací kaskáda
        assertThat(line.getCatalogNumber().getValue()).isEqualTo("ISD-100");
        assertThat(line.getName().getValue()).isEqualTo("Brzdové destičky přední");
        assertThat(line.getUnit().getValue()).isEqualTo("ks");        // C62 → ks
        assertThat(line.getQuantity().getValue()).isEqualByComparingTo("2");
        assertThat(line.getUnitPriceExclVat().getValue()).isEqualByComparingTo("500.00");
        assertThat(line.getVatRate().getValue()).isEqualTo(21);
        assertThat(line.getTotalExclVat().getValue()).isEqualByComparingTo("1000.00");
        assertThat(line.getTotalInclVat().getValue()).isEqualByComparingTo("1210.00");

        // rekapitulace DPH z TaxSubTotal (ne z vnořeného TaxAmount)
        assertThat(draft.getVatRecap()).hasSize(1);
        assertThat(draft.getVatRecap().get(0).getRatePercent()).isEqualTo(21);
        assertThat(draft.getVatRecap().get(0).getBase()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("import uloží jen draft (0 skladových dat) a projde touž verifikací")
    void importStoresOnlyDraft() throws Exception {
        mockMvc.perform(multipart(URL).file(file(invoiceXml("1", "ISD-2026-2")))
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNumber").value("ISD-2026-2"))
                .andExpect(jsonPath("$.reconciliationOk").value(true))
                .andExpect(jsonPath("$.items[0].sku").value("ISD-100"));

        assertThat(count("warehouse.products")).isZero();
        assertThat(count("warehouse.goods_receipt_items")).isZero();
        assertThat(count("warehouse.stock_movements")).isZero();

        String source = jdbc.queryForObject(
                "SELECT source_channel::text FROM warehouse.goods_receipts WHERE invoice_number = 'ISD-2026-2'",
                String.class);
        assertThat(source).isEqualTo("ISDOC");
    }

    @Test
    @DisplayName("dobropis → 422: naskladnil by zboží místo odepsání")
    void creditNoteRejected() throws Exception {
        mockMvc.perform(multipart(URL).file(file(invoiceXml("5", "ISD-DOB-1")))
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("ISDOC_UNSUPPORTED_DOCUMENT_TYPE"));

        assertThat(count("warehouse.goods_receipts")).isZero();
    }

    @Test
    @DisplayName("rozbité XML → 400")
    void brokenXmlRejected() throws Exception {
        mockMvc.perform(multipart(URL).file(file("<Invoice><ID>x</ID>"))
                        .with(user(admin())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("neznámý kód jednotky projde beze změny — kontrolor ho uvidí a opraví")
    void unknownUnitCodeKeptAsIs() {
        String xml = invoiceXml("1", "ISD-UNIT").replace("unitCode=\"C62\"", "unitCode=\"XYZ\"");
        var draft = parser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(draft.getLines().get(0).getUnit().getValue()).isEqualTo("XYZ");
    }
}
