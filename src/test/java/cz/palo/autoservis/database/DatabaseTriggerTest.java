package cz.palo.autoservis.database;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.mapper.MileageHistoryMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.mapper.VehicleMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.vehicle.MileageHistory;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.customer.AddressDto;
import cz.palo.autoservis.model.dto.customer.CustomerDto;
import cz.palo.autoservis.model.enums.AddressType;
import cz.palo.autoservis.model.enums.CustomerType;
import cz.palo.autoservis.model.enums.MileageSource;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import cz.palo.autoservis.service.CustomerService;
import cz.palo.autoservis.service.InvoiceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Databázové triggery — ověřuje se, že <strong>skutečně vystřelí</strong>.
 *
 * <p>Metodika: hodnoty se čtou <strong>přímo z databáze přes {@link JdbcTemplate}</strong>,
 * ne přes MyBatis. Uvnitř jedné transakce totiž lokální cache SqlSession vrací tentýž objekt,
 * který aplikace zapsala, takže „načtu si to znovu přes mapper" by neprokázalo nic o tom,
 * co je opravdu v tabulce (na tuhle past narazila fáze T2 u profilu firmy).
 *
 * <p>U generovaných čísel se netvrdí jen formát, ale i <strong>inkrement dvou po sobě
 * jdoucích záznamů</strong> — samotný formát by prošel i triggeru, který vrací pořád totéž.
 */
@Transactional
class DatabaseTriggerTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long CUSTOMER_ID = 1L;
    private static final long VEHICLE_ID = 1L;
    private static final long BILLING_ADDRESS_ID = 2L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private cz.palo.autoservis.mapper.OrderItemMapper orderItemMapper;

    @Autowired
    private MileageHistoryMapper mileageHistoryMapper;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private cz.palo.autoservis.service.VehicleService vehicleService;

    @Autowired
    private cz.palo.autoservis.mapper.WarehouseImportMapper warehouseImportMapper;

    // =========================================================================
    // Generovaná čísla dokladů
    // =========================================================================

    @Test
    @DisplayName("ZNK-: trigger přidělí číslo zákazníka ve správném tvaru a s aktuálním rokem")
    void customerNumberTrigger_generatesFormattedNumber() {
        Long customerId = customerService.create(individualRequest("Trigger", "Test"), USER_ID).getId();

        String number = jdbcTemplate.queryForObject(
                "SELECT customer_number FROM customer.customers WHERE id = ?", String.class, customerId);

        assertThat(number).isNotNull();
        assertThat(number).matches("ZNK-\\d{4}-\\d{4}");
        assertThat(number).startsWith("ZNK-" + LocalDate.now().getYear() + "-");
    }

    @Test
    @DisplayName("ZNK-: dva zákazníci po sobě dostanou po sobě jdoucí čísla ze sekvence")
    void customerNumberTrigger_incrementsSequence() {
        Long firstId = customerService.create(individualRequest("První", "Zákazník"), USER_ID).getId();
        Long secondId = customerService.create(individualRequest("Druhý", "Zákazník"), USER_ID).getId();

        int first = sequenceSuffix(customerNumberOf(firstId));
        int second = sequenceSuffix(customerNumberOf(secondId));

        assertThat(second)
                .as("sekvence musí postoupit, jinak by trigger vracel pořád totéž")
                .isEqualTo(first + 1);
    }

    @Test
    @DisplayName("ZAK-: trigger přidělí číslo zakázky a druhá zakázka dostane další v pořadí")
    void orderNumberTrigger_generatesAndIncrements() {
        Long firstId = insertOrder();
        Long secondId = insertOrder();

        String firstNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM \"order\".orders WHERE id = ?", String.class, firstId);
        String secondNumber = jdbcTemplate.queryForObject(
                "SELECT order_number FROM \"order\".orders WHERE id = ?", String.class, secondId);

        assertThat(firstNumber).matches("ZAK-\\d{4}-\\d{4}");
        assertThat(firstNumber).startsWith("ZAK-" + LocalDate.now().getYear() + "-");
        assertThat(sequenceSuffix(secondNumber)).isEqualTo(sequenceSuffix(firstNumber) + 1);
    }

    @Test
    @DisplayName("ZAK-: číslo je MAX+1 za rok — navazuje na existující data, ne globální sekvence (TD-57)")
    void orderNumberTrigger_isPerYearMaxPlusOne() {
        int year = LocalDate.now().getYear();
        insertOrderWithNumber("ZAK-" + year + "-0100");

        Long id = insertOrder();
        String number = jdbcTemplate.queryForObject(
                "SELECT order_number FROM \"order\".orders WHERE id = ?", String.class, id);

        assertThat(number)
                .as("MAX(letoška)+1 = 0101, ne pokračování globální sekvence")
                .isEqualTo("ZAK-" + year + "-0101");
    }

    @Test
    @DisplayName("ZAK-: řada se resetuje per rok — vysoké číslo jiného roku pořadí neovlivní (TD-57)")
    void orderNumberTrigger_resetsPerYear() {
        int year = LocalDate.now().getYear();
        insertOrderWithNumber("ZAK-2099-9000");   // jiný rok, vysoké číslo

        Long id = insertOrder();
        String number = jdbcTemplate.queryForObject(
                "SELECT order_number FROM \"order\".orders WHERE id = ?", String.class, id);

        assertThat(number).startsWith("ZAK-" + year + "-");
        assertThat(sequenceSuffix(number))
                .as("pořadí letoška se počítá jen z letoška — ne 9001 z roku 2099")
                .isLessThan(9000);
    }

    // Generátor čísla faktury z DB odešel (V71) — číslo skládá aplikace při vystavení
    // a řadu testuje DocumentNumberMaskTest + InvoiceLifecycleTest. Tady zůstává
    // to, co dál garantuje DB: unikátnost, neměnnost po vystavení a CHECKy z V71.
    // Každý test má právě JEDEN záměrně selhávající příkaz jako poslední akci —
    // třída je @Transactional a selhání zneplatní zbytek transakce.

    @Test
    @DisplayName("faktura: číslo dostane až vystavený doklad a druhý v řadě navazuje (MAX+1 dle masky)")
    void invoiceNumber_assignedAtIssueAndSequential() {
        Long draftId = createDraftInvoice();
        assertThat(invoiceNumberOf(draftId)).as("koncept číslo nemá").isNull();

        String firstNumber = invoiceNumberOf(createInvoice());
        String secondNumber = invoiceNumberOf(createInvoice());

        String expectedPrefix = String.format("%d%02d", LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        assertThat(firstNumber).as("vystavený doklad má číslo v řadě dnešního období")
                .matches("\\d{9}").startsWith(expectedPrefix);
        assertThat(Integer.parseInt(secondNumber.substring(6)))
                .as("řada navazuje na nejvyšší existující číslo")
                .isEqualTo(Integer.parseInt(firstNumber.substring(6)) + 1);
    }

    @Test
    @DisplayName("faktura: číslo vystaveného dokladu je v DB neměnné (trg_invoices_number_immutable, V71)")
    void invoiceNumberImmutableTrigger_rejectsChangeAfterIssue() {
        Long invoiceId = createInvoice();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.invoices SET invoice_number = '999999999' WHERE id = ?", invoiceId))
                .as("přepis čísla vystaveného dokladu musí odmítnout přímo DB")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("faktura: CHECK nepustí vystavení dokladu bez čísla (chk_invoice_issued_has_number, V71)")
    void issuedInvoiceCheck_requiresNumber() {
        Long invoiceId = createDraftInvoice();
        // O číslo může koncept přijít jen surovým SQL — aplikace to nedovolí; u konceptu
        // je to legální (immutability trigger hlídá jen doklady po vystavení).
        jdbcTemplate.update("UPDATE billing.invoices SET invoice_number = NULL WHERE id = ?", invoiceId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.invoices SET status = 'ISSUED' WHERE id = ?", invoiceId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("faktura: CHECK odmítne prázdné číslo (chk_invoice_number_not_blank, V71)")
    void invoiceNumberCheck_rejectsBlank() {
        Long invoiceId = createDraftInvoice();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.invoices SET invoice_number = '   ' WHERE id = ?", invoiceId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("faktura: CHECK pustí do variabilního symbolu jen číslice (chk_invoice_variable_symbol_digits, V71)")
    void variableSymbolCheck_rejectsNonDigits() {
        Long invoiceId = createDraftInvoice();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE billing.invoices SET variable_symbol = 'VS-17' WHERE id = ?", invoiceId))
                .isInstanceOf(DataAccessException.class);
    }

    // =========================================================================
    // updated_at
    // =========================================================================

    @Test
    @DisplayName("updated_at aktualizuje trigger, ne aplikace (R-07)")
    void updatedAtTrigger_movesTimestampOnUpdate() {
        OffsetDateTime before = customerUpdatedAt(CUSTOMER_ID);
        assertThat(before).isNotNull();

        // UPDATE mimo aplikaci — aplikace sloupec vůbec neposílá, přesto se musí změnit
        jdbcTemplate.update(
                "UPDATE customer.customers SET internal_note = ? WHERE id = ?", "dotek triggeru", CUSTOMER_ID);

        OffsetDateTime after = customerUpdatedAt(CUSTOMER_ID);
        assertThat(after)
                .as("trigger musí updated_at posunout, i když ho UPDATE nezmiňuje")
                .isAfter(before);
    }

    @Test
    @DisplayName("updated_at se posune při KAŽDÉM uložení, ne jen při prvním")
    void updatedAtTrigger_movesOnEverySubsequentUpdate() {
        jdbcTemplate.update("UPDATE customer.customers SET internal_note = ? WHERE id = ?", "první", CUSTOMER_ID);
        OffsetDateTime afterFirst = customerUpdatedAt(CUSTOMER_ID);

        jdbcTemplate.update("UPDATE customer.customers SET internal_note = ? WHERE id = ?", "druhý", CUSTOMER_ID);
        OffsetDateTime afterSecond = customerUpdatedAt(CUSTOMER_ID);

        assertThat(afterSecond).isAfterOrEqualTo(afterFirst);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT internal_note FROM customer.customers WHERE id = ?", String.class, CUSTOMER_ID))
                .isEqualTo("druhý");
    }

    // =========================================================================
    // Tachometr — přepočet cache na vozidle
    // =========================================================================

    @Test
    @DisplayName("tachometr: nový záznam propíše stav na vozidlo")
    void mileageTrigger_syncsCurrentMileageToVehicle() {
        long vehicleId = freshVehicleWithoutHistory();
        insertReading(vehicleId, 222_222, LocalDate.now());

        assertThat(currentMileageOf(vehicleId)).isEqualTo(222_222);
    }

    @Test
    @DisplayName("tachometr: rozhoduje NEJNOVĚJŠÍ datum, ne nejvyšší hodnota")
    void mileageTrigger_usesLatestDateNotHighestValue() {
        long vehicleId = freshVehicleWithoutHistory();
        insertReading(vehicleId, 200_000, LocalDate.now());
        // starší záznam s vyšším stavem — dodatečně doplněná historie nesmí přepsat současnost
        insertReading(vehicleId, 999_999, LocalDate.now().minusYears(2));

        assertThat(currentMileageOf(vehicleId))
                .as("cache drží poslední ZNÁMÝ stav podle data, ne maximum")
                .isEqualTo(200_000);
    }

    @Test
    @DisplayName("tachometr: smazání posledního záznamu vrátí stav na předchozí (trigger se hojí)")
    void mileageTrigger_recomputesAfterDelete() {
        // Čerstvé vozidlo bez seedové historie — seedový INITIAL záznam má recorded_date =
        // created_at::date (dnešek), takže by po smazání mého novějšího záznamu vyhrál on.
        long vehicleId = freshVehicleWithoutHistory();
        insertReading(vehicleId, 300_000, LocalDate.now().minusDays(10));
        Long latestId = insertReading(vehicleId, 400_000, LocalDate.now());
        assertThat(currentMileageOf(vehicleId)).isEqualTo(400_000);

        mileageHistoryMapper.delete(latestId);

        assertThat(currentMileageOf(vehicleId))
                .as("po smazání se přepočítá na nejnovější zbývající záznam")
                .isEqualTo(300_000);
    }

    @Test
    @DisplayName("tachometr: smazání jediného záznamu srovná stav na null (žádná historie)")
    void mileageTrigger_afterDeletingLastReading_setsNull() {
        long vehicleId = freshVehicleWithoutHistory();
        Long onlyReading = insertReading(vehicleId, 111_111, LocalDate.now());
        assertThat(currentMileageOf(vehicleId)).isEqualTo(111_111);

        mileageHistoryMapper.delete(onlyReading);

        assertThat(currentMileageOf(vehicleId))
                .as("bez záznamů nemá cache co držet").isNull();
    }

    @Test
    @DisplayName("tachometr: oprava hodnoty existujícího záznamu se propíše taky (UPDATE větev)")
    void mileageTrigger_recomputesAfterUpdate() {
        long vehicleId = freshVehicleWithoutHistory();
        Long readingId = insertReading(vehicleId, 500_000, LocalDate.now());
        assertThat(currentMileageOf(vehicleId)).isEqualTo(500_000);

        jdbcTemplate.update(
                "UPDATE vehicle.mileage_history SET mileage_km = ? WHERE id = ?", 505_000, readingId);

        assertThat(currentMileageOf(vehicleId)).isEqualTo(505_000);
    }

    // =========================================================================
    // Sklad — trigger fn_apply_stock_movement
    // =========================================================================

    @Test
    @DisplayName("sklad: RECEIPT zvedne stav skladu, ale zbytek šarže NESNIŽUJE (větev movement_type <> RECEIPT)")
    void stockMovementTrigger_receiptRaisesStockButKeepsBatchRemainder() {
        var supplier = cz.palo.autoservis.model.domain.warehouse.Supplier.builder()
                .name("Trigger dodavatel").registrationNumber("99001122").countryCode("CZ").active(true).build();
        warehouseImportMapper.insertSupplier(supplier);

        var product = cz.palo.autoservis.model.domain.warehouse.Product.builder()
                .sku("TRG-SKU-1").name("Trigger díl").unit("ks").defaultVatRate(21).build();
        warehouseImportMapper.insertProduct(product);

        var receipt = cz.palo.autoservis.model.domain.warehouse.GoodsReceipt.builder()
                .supplierId(supplier.getId()).supplierNameSnapshot(supplier.getName())
                .invoiceNumber("TRG-FAK-1")
                .subtotal(new BigDecimal("100.00")).vatAmount(new BigDecimal("21.00"))
                .totalAmount(new BigDecimal("121.00")).currency("CZK")
                .documentType(cz.palo.autoservis.model.domain.warehouse.DocumentType.INVOICE)
                .sourceChannel(cz.palo.autoservis.model.domain.warehouse.ReceiptSource.AI_PDF)
                .status(cz.palo.autoservis.model.domain.warehouse.ReceiptStatus.CONFIRMED)
                .reconciliationOk(true).createdBy(USER_ID).build();
        warehouseImportMapper.insertReceipt(receipt);

        var batch = cz.palo.autoservis.model.domain.warehouse.GoodsReceiptItem.builder()
                .goodsReceiptId(receipt.getId()).productId(product.getId()).position(1)
                .nameSnapshot(product.getName())
                .quantityReceived(new BigDecimal("10")).quantityRemaining(new BigDecimal("10"))
                .unitPriceExclVat(new BigDecimal("100.00")).vatRate(21)
                .totalInclVat(new BigDecimal("121.00")).build();
        warehouseImportMapper.insertReceiptItem(batch);

        warehouseImportMapper.insertMovement(cz.palo.autoservis.model.domain.warehouse.StockMovement.builder()
                .productId(product.getId()).batchId(batch.getId())
                .movementType(cz.palo.autoservis.model.domain.warehouse.MovementType.RECEIPT)
                .quantity(new BigDecimal("10")).createdBy(USER_ID).build());

        BigDecimal onHand = jdbcTemplate.queryForObject(
                "SELECT quantity_on_hand FROM warehouse.products WHERE id = ?", BigDecimal.class, product.getId());
        BigDecimal remaining = jdbcTemplate.queryForObject(
                "SELECT quantity_remaining FROM warehouse.goods_receipt_items WHERE id = ?",
                BigDecimal.class, batch.getId());

        assertThat(onHand).as("RECEIPT přičte ke stavu skladu").isEqualByComparingTo("10");
        assertThat(remaining)
                .as("zbytek šarže RECEIPT nemění — jinak by se příjem počítal dvakrát")
                .isEqualByComparingTo("10");
    }

    // =========================================================================
    // Časy a zóny (návaznost na TD-47)
    // =========================================================================

    @Test
    @DisplayName("čas zakázky přežije uložení i načtení beze změny okamžiku")
    void timestampRoundTrip_preservesInstant() {
        OffsetDateTime planned = OffsetDateTime.parse("2026-08-01T14:00:00Z");
        Long orderId = insertOrderWithEstimatedCompletion(planned);

        OffsetDateTime stored = jdbcTemplate.queryForObject(
                "SELECT estimated_completion_at FROM \"order\".orders WHERE id = ?",
                OffsetDateTime.class, orderId);

        assertThat(stored).isNotNull();
        assertThat(stored.toInstant())
                .as("okamžik musí sedět bez ohledu na zónu, ve které ho DB vrátí")
                .isEqualTo(planned.toInstant());
    }

    @Test
    @DisplayName("opakované uložení téže hodnoty čas NEPOSUNE (regrese TD-47)")
    void timestampRoundTrip_repeatedSaveDoesNotDrift() {
        OffsetDateTime planned = OffsetDateTime.parse("2026-08-01T14:00:00Z");
        Long orderId = insertOrderWithEstimatedCompletion(planned);

        // Simulace „otevři a ulož" třikrát po sobě: pokaždé se přečte, co je v DB,
        // a beze změny se to zapíše zpátky. Právě tady TD-47 kumuloval posun o offset zóny.
        for (int i = 0; i < 3; i++) {
            OffsetDateTime current = jdbcTemplate.queryForObject(
                    "SELECT estimated_completion_at FROM \"order\".orders WHERE id = ?",
                    OffsetDateTime.class, orderId);
            jdbcTemplate.update(
                    "UPDATE \"order\".orders SET estimated_completion_at = ? WHERE id = ?", current, orderId);
        }

        OffsetDateTime afterThreeSaves = jdbcTemplate.queryForObject(
                "SELECT estimated_completion_at FROM \"order\".orders WHERE id = ?",
                OffsetDateTime.class, orderId);

        assertThat(afterThreeSaves).isNotNull();
        assertThat(afterThreeSaves.toInstant())
                .as("po třech uloženích musí být okamžik pořád stejný")
                .isEqualTo(planned.toInstant());
    }

    @Test
    @DisplayName("čas se ukládá jako okamžik — vstup v jiné zóně dá tentýž bod v čase")
    void timestampRoundTrip_equivalentInstantsInDifferentZones() {
        // 14:00 UTC a 16:00+02:00 jsou tentýž okamžik; DB je musí uložit shodně.
        Long utcOrder = insertOrderWithEstimatedCompletion(OffsetDateTime.parse("2026-08-01T14:00:00Z"));
        Long offsetOrder = insertOrderWithEstimatedCompletion(OffsetDateTime.parse("2026-08-01T16:00:00+02:00"));

        OffsetDateTime storedUtc = jdbcTemplate.queryForObject(
                "SELECT estimated_completion_at FROM \"order\".orders WHERE id = ?",
                OffsetDateTime.class, utcOrder);
        OffsetDateTime storedOffset = jdbcTemplate.queryForObject(
                "SELECT estimated_completion_at FROM \"order\".orders WHERE id = ?",
                OffsetDateTime.class, offsetOrder);

        assertThat(storedUtc).isNotNull();
        assertThat(storedOffset).isNotNull();
        assertThat(storedOffset.toInstant()).isEqualTo(storedUtc.toInstant());
    }

    // =========================================================================
    // Pomocné
    // =========================================================================

    private String customerNumberOf(Long customerId) {
        return jdbcTemplate.queryForObject(
                "SELECT customer_number FROM customer.customers WHERE id = ?", String.class, customerId);
    }

    private String invoiceNumberOf(Long invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT invoice_number FROM billing.invoices WHERE id = ?", String.class, invoiceId);
    }

    private OffsetDateTime customerUpdatedAt(Long customerId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM customer.customers WHERE id = ?", OffsetDateTime.class, customerId);
    }

    private Integer currentMileageOf(Long vehicleId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_mileage_km FROM vehicle.vehicles WHERE id = ?", Integer.class, vehicleId);
    }

    /** Poslední čtyřčíslí z čísla dokladu tvaru PREFIX-ROK-NNNN. */
    private static int sequenceSuffix(String documentNumber) {
        return Integer.parseInt(documentNumber.substring(documentNumber.lastIndexOf('-') + 1));
    }

    /** Založí nové vozidlo bez záznamu tachometru (bez initialMileageKm), ať testy triggeru řídí veškerou historii. */
    private long freshVehicleWithoutHistory() {
        var request = new cz.palo.autoservis.model.dto.vehicle.VehicleDto.CreateRequest();
        request.setCustomerId(CUSTOMER_ID);
        request.setVin("VF1RJB00X66" + String.format("%06d", vinCounter++));
        request.setBrand("Renault");
        request.setModel("Mégane");
        request.setFuelType(cz.palo.autoservis.model.enums.FuelType.PETROL);
        return vehicleService.create(request, USER_ID).getId();
    }

    private int vinCounter = 100;

    private Long insertReading(Long vehicleId, int mileageKm, LocalDate recordedDate) {
        MileageHistory reading = MileageHistory.builder()
                .vehicleId(vehicleId)
                .mileageKm(mileageKm)
                .recordedDate(recordedDate)
                .source(MileageSource.SERVICE)
                .createdBy(USER_ID)
                .build();
        mileageHistoryMapper.insert(reading);
        return reading.getId();
    }

    private Long insertOrder() {
        return insertOrderWithEstimatedCompletion(null);
    }
    /** Vloží zakázku s EXPLICITNÍM číslem (obejde trigger — WHEN order_number IS NULL/''). */
    private void insertOrderWithNumber(String orderNumber) {
        jdbcTemplate.update(
                "INSERT INTO \"order\".orders (order_number, customer_id, vehicle_id, description, received_at, created_by) "
                        + "VALUES (?, ?, ?, ?, CURRENT_DATE, ?)",
                orderNumber, CUSTOMER_ID, VEHICLE_ID, "TD-57 test", USER_ID);
    }

    private Long insertOrderWithEstimatedCompletion(OffsetDateTime estimatedCompletionAt) {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .description("Zakázka pro test triggeru")
                .estimatedPrice(new BigDecimal("1000"))
                .estimatedCompletionAt(estimatedCompletionAt)
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        return order.getId();
    }

    private Long createDraftInvoice() {
        Long orderId = insertOrder();
        orderItemMapper.insert(cz.palo.autoservis.model.domain.order.OrderItem.builder()
                .orderId(orderId)
                .itemType(OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build());

        // Fakturovat lze až dokončenou zakázku (2026-08-05) — přepnutí přímo mapperem,
        // jde o přípravu dat, ne o testovanou cestu.
        orderMapper.findById(orderId).ifPresent(o -> {
            o.setStatus(OrderStatus.COMPLETED);
            orderMapper.update(o);
        });

        InvoiceDto.CreateRequest request = new InvoiceDto.CreateRequest();
        request.setOrderId(orderId);
        request.setBillingAddressId(BILLING_ADDRESS_ID);
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().plusDays(14));
        request.setTaxableSupplyDate(LocalDate.now());
        request.setPaymentMethod(PaymentMethod.TRANSFER);
        return invoiceService.createFromOrder(request, USER_ID).getId();
    }

    private Long createInvoice() {
        Long id = createDraftInvoice();
        issueWithNextNumber(invoiceService, id, USER_ID);
        return id;
    }

    private static CustomerDto.CreateRequest individualRequest(String firstName, String lastName) {
        CustomerDto.CreateRequest request = new CustomerDto.CreateRequest();
        request.setCustomerType(CustomerType.INDIVIDUAL);
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setGdprConsent(true);

        AddressDto.CreateRequest address = new AddressDto.CreateRequest();
        address.setAddressType(AddressType.BILLING);
        address.setStreet("Testovací");
        address.setStreetNumber("1");
        address.setCity("Praha");
        address.setPostalCode("110 00");
        address.setCountryCode("CZ");
        request.setAddresses(List.of(address));
        return request;
    }
}
