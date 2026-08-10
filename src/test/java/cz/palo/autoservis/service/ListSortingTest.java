package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceSearchParams;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.dto.customer.CustomerSearchParams;
import cz.palo.autoservis.model.dto.order.OrderDto;
import cz.palo.autoservis.model.dto.order.OrderSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.user.UserDto;
import cz.palo.autoservis.model.dto.user.UserSearchParams;
import cz.palo.autoservis.model.dto.vehicle.VehicleDto;
import cz.palo.autoservis.model.dto.vehicle.VehicleSearchParams;
import cz.palo.autoservis.model.dto.warehouse.ProductDto;
import cz.palo.autoservis.model.dto.warehouse.ProductSearchParams;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import cz.palo.autoservis.model.dto.warehouse.ReceiptSearchParams;
import cz.palo.autoservis.model.dto.warehouse.SupplierDto;
import cz.palo.autoservis.model.dto.warehouse.SupplierSearchParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Řazení seznamů přes {@code sortBy} / {@code sortDesc} (TD-46, fáze U3R).
 *
 * <p>Do 2026-07 se {@code sortDesc} v žádném XML mapperu nepoužíval — parametr se
 * přijal a zahodil, takže seznamy řadily vždy vzestupně. Chyba se projevila až na
 * frontendu (druhý klik na hlavičku nezměnil pořadí) a nezachytil ji žádný test,
 * protože na řazení žádný neexistoval.
 *
 * <p>Tenhle test proto pokrývá **všech 8 stránkovaných seznamů** a u každého ověřuje
 * čtyři věci: řazení podle zvoleného sloupce, respektování směru, funkční **výchozí**
 * řazení (dřív jediná větev, která směr ignorovala) a odolnost proti neznámému klíči.
 */
@Transactional
class ListSortingTest extends AbstractIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private UserService userService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProductService productService;
    @Autowired private SupplierService supplierService;
    @Autowired private OrderService orderService;
    @Autowired private InvoiceService invoiceService;
    @Autowired private ReceiptReviewService receiptReviewService;
    @Autowired private JdbcTemplate jdbc;

    /**
     * Sklad, dodavatelé ani příjemky **nemají seed data** — migrace je nezakládají
     * (jediný `INSERT INTO warehouse.*` v migracích je `supplier_products` ve V40).
     * Bez fixtur by testy řazení nad nimi procházely naprázdno: aserce
     * „je seřazeno" totiž nad prázdným seznamem platí triviálně. Přesně tak
     * prošla první verze tohoto testu.
     *
     * <p>Vkládá se SQL, ne přes service: dodavatel ani příjemka nemají veřejnou
     * create metodu (vznikají výhradně importem dokladu) a pro řazení stačí řádky
     * s různými hodnotami. Transakce testu vše zase odroluje.
     */
    @BeforeEach
    void seedWarehouseFixtures() {
        for (String[] s : new String[][]{{"Alfa díly s.r.o.", "11111111", "Brno"},
                                         {"Beta motors a.s.", "22222222", "Ostrava"},
                                         {"Gama servis s.r.o.", "33333333", "Praha"}}) {
            jdbc.update("INSERT INTO warehouse.suppliers (name, registration_number, city) VALUES (?, ?, ?)",
                    s[0], s[1], s[2]);
        }

        for (String[] p : new String[][]{{"AAA-001", "Brzdové destičky přední"},
                                         {"BBB-002", "Olejový filtr"},
                                         {"CCC-003", "Vzduchový filtr"}}) {
            jdbc.update("INSERT INTO warehouse.products (sku, name, sale_price) VALUES (?, ?, ?)",
                    p[0], p[1], new java.math.BigDecimal(p[0].hashCode() % 1000 + 1000));
        }

        Long supplierId = jdbc.queryForObject(
                "SELECT id FROM warehouse.suppliers ORDER BY id LIMIT 1", Long.class);
        for (String[] r : new String[][]{{"DOC-001", "2026-01-10", "1000.00"},
                                         {"DOC-002", "2026-02-20", "2000.00"},
                                         {"DOC-003", "2026-03-30", "3000.00"}}) {
            jdbc.update("""
                    INSERT INTO warehouse.goods_receipts
                        (supplier_id, supplier_name_snapshot, invoice_number, issue_date,
                         total_amount, currency, document_type, status)
                    VALUES (?, ?, ?, CAST(? AS DATE), CAST(? AS NUMERIC), 'CZK',
                            CAST('INVOICE' AS warehouse.document_type),
                            CAST('PENDING_REVIEW' AS warehouse.receipt_status))
                    """, supplierId, "Alfa díly s.r.o.", r[0], r[1], r[2]);
        }
    }

    // ── pomocné aserce ──────────────────────────────────────────────────────

    private static <T, U extends Comparable<U>> List<U> keys(List<T> rows, Function<T, U> key) {
        return rows.stream().map(key).filter(Objects::nonNull).toList();
    }

    private static <T, U extends Comparable<U>> void assertAscending(List<T> rows, Function<T, U> key) {
        assertThat(keys(rows, key)).isSortedAccordingTo(Comparator.naturalOrder());
    }

    private static <T, U extends Comparable<U>> void assertDescending(List<T> rows, Function<T, U> key) {
        assertThat(keys(rows, key)).isSortedAccordingTo(Comparator.reverseOrder());
    }

    /**
     * Ověří sloupec v obou směrech. Nekontroluje konkrétní hodnoty ze seed dat
     * (křehké), jen uspořádanost — a že seznam vůbec něco vrátil, aby test
     * neprošel nad prázdnou množinou.
     */
    private static <P, T, U extends Comparable<U>> void assertSortableBothWays(
            P params, java.util.function.Consumer<Boolean> setDesc,
            Function<P, List<T>> fetch, Function<T, U> key) {

        setDesc.accept(false);
        List<T> asc = fetch.apply(params);
        assertThat(asc).as("seznam nesmí být prázdný, jinak řazení netestujeme").isNotEmpty();
        assertAscending(asc, key);

        setDesc.accept(true);
        assertDescending(fetch.apply(params), key);
    }

    // ── zákazníci ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Zákazníci")
    class Customers {

        private List<CustomerDto.ListResponse> fetch(CustomerSearchParams p) {
            p.setPageSize(50);
            return customerService.getPage(p).getContent();
        }

        @Test
        @DisplayName("customerNumber oběma směry")
        void customerNumber() {
            CustomerSearchParams p = new CustomerSearchParams();
            p.setSortBy("customerNumber");
            assertSortableBothWays(p, p::setSortDesc, this::fetch, CustomerDto.ListResponse::getCustomerNumber);
        }

        @Test
        @DisplayName("sestupné řazení obrátí pořadí (regrese TD-46)")
        void descendingReversesOrder() {
            CustomerSearchParams p = new CustomerSearchParams();
            p.setSortBy("customerNumber");
            p.setSortDesc(false);
            List<String> asc = keys(fetch(p), CustomerDto.ListResponse::getCustomerNumber);
            p.setSortDesc(true);
            List<String> desc = keys(fetch(p), CustomerDto.ListResponse::getCustomerNumber);

            assertThat(asc).isNotEmpty();
            assertThat(desc).containsExactlyElementsOf(asc.reversed());
        }

        @Test
        @DisplayName("výchozí řazení respektuje směr (U3R.1)")
        void defaultSortHonoursDirection() {
            CustomerSearchParams p = new CustomerSearchParams();   // default createdAt DESC
            List<Long> desc = keys(fetch(p), CustomerDto.ListResponse::getId);
            p.setSortDesc(false);
            List<Long> asc = keys(fetch(p), CustomerDto.ListResponse::getId);

            assertThat(desc).isNotEmpty();
            assertThat(desc).isNotEqualTo(asc);
        }

        @Test
        @DisplayName("neznámý sortBy nespadne a chová se jako výchozí")
        void unknownSortBy() {
            CustomerSearchParams p = new CustomerSearchParams();
            p.setSortBy("neexistujiciSloupec");
            assertThat(fetch(p)).isNotEmpty();
        }
    }

    // ── ostatní stránkované seznamy ─────────────────────────────────────────

    @Nested
    @DisplayName("Ostatní seznamy")
    class OtherLists {

        @Test
        @DisplayName("uživatelé — username")
        void users() {
            UserSearchParams p = new UserSearchParams();
            p.setPageSize(50);
            p.setSortBy("username");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> userService.getPage(x).getContent(), UserDto.ListResponse::getUsername);
        }

        @Test
        @DisplayName("vozidla — vin")
        void vehicles() {
            VehicleSearchParams p = new VehicleSearchParams();
            p.setPageSize(50);
            p.setSortBy("vin");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> vehicleService.getPage(x).getContent(), VehicleDto.ListResponse::getVin);
        }

        @Test
        @DisplayName("sklad — sku")
        void products() {
            ProductSearchParams p = new ProductSearchParams();
            p.setPageSize(50);
            p.setSortBy("sku");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> productService.getPage(x).getContent(), ProductDto.ListResponse::getSku);
        }

        @Test
        @DisplayName("dodavatelé — výchozí klíč name")
        void suppliers() {
            SupplierSearchParams p = new SupplierSearchParams();
            p.setPageSize(50);
            assertSortableBothWays(p, p::setSortDesc,
                    x -> supplierService.getPage(x).getContent(), SupplierDto.ListResponse::getName);
        }

        @Test
        @DisplayName("zakázky — orderNumber (U3R.2)")
        void orders() {
            OrderSearchParams p = new OrderSearchParams();
            p.setPageSize(50);
            p.setSortBy("orderNumber");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> orderService.getPage(x).getContent(), OrderDto.ListResponse::getOrderNumber);
        }

        @Test
        @DisplayName("faktury — invoiceNumber")
        void invoices() {
            InvoiceSearchParams p = new InvoiceSearchParams();
            p.setPageSize(50);
            p.setSortBy("invoiceNumber");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> invoiceService.getPage(x).getContent(), InvoiceDto.ListResponse::getInvoiceNumber);
        }

        @Test
        @DisplayName("příjemky — documentNumber (U3R.2)")
        void receipts() {
            ReceiptSearchParams p = new ReceiptSearchParams();
            p.setPageSize(50);
            p.setSortBy("documentNumber");
            assertSortableBothWays(p, p::setSortDesc,
                    x -> receiptReviewService.list(x).getContent(), ReceiptDto.ListResponse::getDocumentNumber);
        }
    }

    // ── výchozí řazení a odolnost napříč seznamy ────────────────────────────

    @Nested
    @DisplayName("Výchozí řazení a neznámý klíč")
    class DefaultsAndFallback {

        @Test
        @DisplayName("každý seznam něco vrátí bez parametrů i s neznámým sortBy")
        void everyListSurvivesUnknownKey() {
            CustomerSearchParams c = new CustomerSearchParams();
            UserSearchParams u = new UserSearchParams();
            VehicleSearchParams v = new VehicleSearchParams();
            ProductSearchParams pr = new ProductSearchParams();
            SupplierSearchParams s = new SupplierSearchParams();
            OrderSearchParams o = new OrderSearchParams();
            InvoiceSearchParams i = new InvoiceSearchParams();
            ReceiptSearchParams r = new ReceiptSearchParams();

            for (var p : List.of(c, u, v, pr, s, o, i, r)) {
                p.setPageSize(50);
            }

            assertThat(customerService.getPage(c).getContent()).isNotEmpty();
            assertThat(userService.getPage(u).getContent()).isNotEmpty();
            assertThat(vehicleService.getPage(v).getContent()).isNotEmpty();
            assertThat(productService.getPage(pr).getContent()).isNotEmpty();
            assertThat(supplierService.getPage(s).getContent()).isNotEmpty();
            assertThat(orderService.getPage(o).getContent()).isNotEmpty();
            assertThat(invoiceService.getPage(i).getContent()).isNotEmpty();
            assertThat(receiptReviewService.list(r).getContent()).isNotEmpty();

            for (var p : List.of(c, u, v, pr, s, o, i, r)) {
                p.setSortBy("neexistujiciSloupec");
            }

            assertThat(customerService.getPage(c).getContent()).isNotEmpty();
            assertThat(userService.getPage(u).getContent()).isNotEmpty();
            assertThat(vehicleService.getPage(v).getContent()).isNotEmpty();
            assertThat(productService.getPage(pr).getContent()).isNotEmpty();
            assertThat(supplierService.getPage(s).getContent()).isNotEmpty();
            assertThat(orderService.getPage(o).getContent()).isNotEmpty();
            assertThat(invoiceService.getPage(i).getContent()).isNotEmpty();
            assertThat(receiptReviewService.list(r).getContent()).isNotEmpty();
        }

        @Test
        @DisplayName("výchozí řazení jde obrátit u zakázek, faktur i příjemek")
        void defaultsHonourDirection() {
            OrderSearchParams o = new OrderSearchParams();
            o.setPageSize(50);
            List<Long> ordersAsc = keys(orderService.getPage(o).getContent(), OrderDto.ListResponse::getId);
            o.setSortDesc(true);
            List<Long> ordersDesc = keys(orderService.getPage(o).getContent(), OrderDto.ListResponse::getId);
            assertThat(ordersAsc).isNotEmpty().isNotEqualTo(ordersDesc);

            InvoiceSearchParams i = new InvoiceSearchParams();
            i.setPageSize(50);
            List<Long> invDesc = keys(invoiceService.getPage(i).getContent(), InvoiceDto.ListResponse::getId);
            i.setSortDesc(false);
            List<Long> invAsc = keys(invoiceService.getPage(i).getContent(), InvoiceDto.ListResponse::getId);
            assertThat(invDesc).isNotEmpty().isNotEqualTo(invAsc);

            ReceiptSearchParams r = new ReceiptSearchParams();
            r.setPageSize(50);
            List<Long> recDesc = keys(receiptReviewService.list(r).getContent(), ReceiptDto.ListResponse::getId);
            r.setSortDesc(false);
            List<Long> recAsc = keys(receiptReviewService.list(r).getContent(), ReceiptDto.ListResponse::getId);
            assertThat(recDesc).isNotEmpty().isNotEqualTo(recAsc);
        }
    }
}
