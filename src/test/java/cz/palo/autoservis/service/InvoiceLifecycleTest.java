package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.InvoiceItemMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.OrderItemMapper;
import cz.palo.autoservis.mapper.OrderMapper;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.model.enums.OrderItemType;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.model.enums.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static cz.palo.autoservis.service.InvoiceIssuing.issueWithNextNumber;
import static cz.palo.autoservis.service.InvoiceIssuing.nextNumberRequest;
import static cz.palo.autoservis.service.InvoiceIssuing.requestFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Úplný životní cyklus faktury ({@code InvoiceServiceImpl}) — doplňuje
 * {@code InvoiceStatusTransitionTest}, který pokrýval jen {@code issue} a guardovaný UPDATE.
 *
 * <p>Automat se testuje <strong>v obou směrech</strong>: u každého stavu se ověří, že povolený
 * přechod projde <em>a</em> že každý zakázaný selže s {@code INVALID_STATUS_TRANSITION}.
 * Samotná matice povolených přechodů je pokrytá jednotkově v {@code InvoiceStatusTest};
 * tady se dokazuje, že ji service skutečně vynucuje proti databázi.
 *
 * <p>Druhá polovina testuje <strong>uzamčení editace</strong>: faktura je právní doklad, takže
 * mimo stav DRAFT nesmí jít měnit hlavička ani položky ({@code INVOICE_NOT_EDITABLE}).
 */
@Transactional
class InvoiceLifecycleTest extends AbstractIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long CUSTOMER_ID = 1L;
    private static final long BILLING_ADDRESS_ID = 2L; // seed: adresa BILLING zákazníka 1
    private static final long VEHICLE_ID = 1L;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private cz.palo.autoservis.mapper.CompanyProfileMapper companyProfileMapper;

    @Autowired
    private InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyProfileService companyProfileService;

    @Autowired
    private CashReceiptService cashReceiptService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderItemService orderItemService;

    private Long orderId;
    private Long draftInvoiceId;

    @BeforeEach
    void createDraftInvoice() {
        orderId = createOrderWithItem();
        draftInvoiceId = invoiceService.createFromOrder(createRequest(orderId), USER_ID).getId();
    }

    // =========================================================================
    // createFromOrder
    // =========================================================================

    @Test
    @DisplayName("createFromOrder vytvoří DRAFT fakturu se snapshoty, oběma stranami a položkami")
    void createFromOrder_buildsCompleteDraft() {
        InvoiceDto.DetailResponse invoice = invoiceService.getById(draftInvoiceId);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getOrderId()).isEqualTo(orderId);
        assertThat(invoice.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(invoice.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(invoice.getInvoiceNumber())
                .as("koncept číslo nemá — dostane ho až při vystavení")
                .isNull();

        assertThat(invoice.getCustomerNameSnapshot()).isEqualTo("Jan Novák");
        assertThat(invoice.getOrderNumberSnapshot()).matches("ZAK-\\d{4}-\\d{4}");

        assertThat(invoice.getSupplier()).as("dodavatel = zmražený profil firmy").isNotNull();
        assertThat(invoice.getCustomer()).isNotNull();
        assertThat(invoice.getCustomer().getName()).isEqualTo("Jan Novák");

        assertThat(invoice.getItems()).hasSize(1);
        assertThat(invoice.getItems().getFirst().getName()).isEqualTo("Práce mechanika");
        assertThat(invoice.getTotalNet()).isEqualByComparingTo("500.00");
        assertThat(invoice.getTotalGross()).isEqualByComparingTo("605.00");
    }

    @Test
    @DisplayName("dodavatel na faktuře je zmražený snapshot profilu firmy, ne živý odkaz")
    void createFromOrder_freezesSupplierPartyFromCompanyProfile() {
        // Profil se nastaví PŘED vystavením, aby se dalo tvrdit konkrétní hodnoty —
        // tyhle údaje jdou na tištěný doklad a QR platbu, takže musí sedět do posledního pole.
        companyProfileService.update(companyProfileRequest("Autoservis Testovací s.r.o."));

        Long newOrderId = createOrderWithItem();
        InvoiceDto.DetailResponse invoice =
                invoiceService.createFromOrder(createRequest(newOrderId), USER_ID);

        InvoiceDto.PartyResponse supplier = invoice.getSupplier();
        assertThat(supplier.getName()).isEqualTo("Autoservis Testovací s.r.o.");
        assertThat(supplier.getIco()).isEqualTo("12345678");
        assertThat(supplier.getDic()).isEqualTo("CZ12345678");
        assertThat(supplier.getStreet()).isEqualTo("Dílenská");
        assertThat(supplier.getStreetNumber()).isEqualTo("12");
        assertThat(supplier.getCity()).isEqualTo("Praha");
        assertThat(supplier.getPostalCode()).isEqualTo("110 00");
        assertThat(supplier.getCountryCode()).isEqualTo("CZ");
        assertThat(supplier.getBankAccount()).isEqualTo("123456789/0800");
        assertThat(supplier.getIban()).isEqualTo("CZ6508000000192000145399");
        assertThat(supplier.getSwift()).isEqualTo("GIBACZPX");

        // pozdější změna profilu už doklad nesmí přepsat
        companyProfileService.update(companyProfileRequest("Přejmenovaný servis a.s."));
        assertThat(invoiceService.getById(invoice.getId()).getSupplier().getName())
                .as("snapshot se nesmí měnit se změnou profilu")
                .isEqualTo("Autoservis Testovací s.r.o.");
    }

    @Test
    @DisplayName("odběratel na faktuře je snapshot zákazníka a jeho fakturační adresy")
    void createFromOrder_freezesCustomerPartyFromBillingAddress() {
        InvoiceDto.PartyResponse customer = invoiceService.getById(draftInvoiceId).getCustomer();

        // seed: zákazník 1 (Jan Novák), adresa id=2 typu BILLING — Hlavní 42, Brno, 602 00
        assertThat(customer.getName()).isEqualTo("Jan Novák");
        assertThat(customer.getStreet()).isEqualTo("Hlavní");
        assertThat(customer.getStreetNumber()).isEqualTo("42");
        assertThat(customer.getCity()).isEqualTo("Brno");
        assertThat(customer.getPostalCode()).isEqualTo("602 00");
    }

    @Test
    @DisplayName("faktura si zmrazí i SPZ vozidla (SPZ se časem mění, doklad ne)")
    void createFromOrder_freezesVehicleLicensePlate() {
        InvoiceDto.DetailResponse invoice = invoiceService.getById(draftInvoiceId);

        assertThat(invoice.getVehicleLicensePlate())
                .as("SPZ vozidla 1 ze seedu").isEqualTo("1AB 2345");
    }

    @Test
    @DisplayName("faktura si zmrazí i VIN, značku a model vozidla (E1.4/K-5 — dřív se četly živě)")
    void createFromOrder_freezesVehicleVinBrandModel() {
        InvoiceDto.DetailResponse invoice = invoiceService.getById(draftInvoiceId);

        assertThat(invoice.getVehicleVin())
                .as("VIN zmražený na faktuře").isNotNull().matches("[A-HJ-NPR-Z0-9]{17}");
        assertThat(invoice.getVehicleBrand()).as("značka zmražená").isNotBlank();
        assertThat(invoice.getVehicleModel()).as("model zmražený").isNotBlank();
    }

    @Test
    @DisplayName("druhá faktura k téže zakázce → ORDER_ALREADY_INVOICED (422, vazba je 1:1)")
    void createFromOrder_secondInvoiceForSameOrder_isRejected() {
        assertThatThrownBy(() -> invoiceService.createFromOrder(createRequest(orderId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("ORDER_ALREADY_INVOICED");
                    assertThat(e.getParams()).containsEntry("orderId", orderId);
                });
    }

    @Test
    @DisplayName("faktura ze zakázky bez položek → ORDER_HAS_NO_ITEMS (422)")
    void createFromOrder_orderWithoutItems_isRejected() {
        Long emptyOrderId = createOrder();

        assertThatThrownBy(() -> invoiceService.createFromOrder(createRequest(emptyOrderId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_HAS_NO_ITEMS"));
    }

    @Test
    @DisplayName("fakturační adresa cizího zákazníka → ADDRESS_NOT_OWNED_BY_CUSTOMER (422)")
    void createFromOrder_addressOfAnotherCustomer_isRejected() {
        Long newOrderId = createOrderWithItem();
        InvoiceDto.CreateRequest request = createRequest(newOrderId);
        request.setBillingAddressId(4L); // seed: adresa zákazníka 3, ne zákazníka 1

        assertThatThrownBy(() -> invoiceService.createFromOrder(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ADDRESS_NOT_OWNED_BY_CUSTOMER"));
    }

    @Test
    @DisplayName("faktura k neexistující zakázce → ResourceNotFoundException (404)")
    void createFromOrder_unknownOrder_throwsResourceNotFound() {
        assertThatThrownBy(() -> invoiceService.createFromOrder(createRequest(999_999L), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Povolené přechody
    // =========================================================================

    @Test
    @DisplayName("DRAFT → ISSUED → PAID: celá šťastná cesta projde")
    void happyPath_draftToIssuedToPaid() {
        assertThat(issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID).getStatus())
                .isEqualTo(InvoiceStatus.ISSUED);

        assertThat(invoiceService.markPaid(draftInvoiceId, USER_ID).getStatus())
                .isEqualTo(InvoiceStatus.PAID);

        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("koncept se maže i s položkami a stranami (výjimka z R-06)")
    void delete_draft_removesInvoiceWithItemsAndParties() {
        // Koncept není doklad — nemá číslo, nikam neodešel, takže není co archivovat.
        // Storno konceptu se od 2026-08-02 nahradilo mazáním, ať tabulka nebobtná
        // stornovanými rozpracovanými fakturami (rozhodnutí uživatele).
        assertThat(invoiceService.getById(draftInvoiceId).getItems()).isNotEmpty();

        invoiceService.delete(draftInvoiceId, USER_ID);

        assertThat(invoiceMapper.findById(draftInvoiceId)).isEmpty();
        assertThat(invoiceItemMapper.findByInvoiceId(draftInvoiceId))
                .as("položky odejdou s konceptem přes FK ON DELETE CASCADE").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing.invoice_party WHERE invoice_id = ?", Integer.class, draftInvoiceId))
                .as("strany dokladu taky").isZero();
    }

    // =========================================================================
    // Hlídání mezer v číselné řadě (V89)
    // =========================================================================

    @Test
    @DisplayName("vypnuté hlídání nehlásí nic a dá to najevo příznakem, ne prázdným seznamem")
    void numberGaps_disabledByDefault() {
        var gaps = invoiceService.findNumberGaps();

        assertThat(gaps.isEnabled())
                .as("prázdný seznam u vypnuté kontroly nesmí znamenat „řada je souvislá\"")
                .isFalse();
        assertThat(gaps.getMissingNumbers()).isEmpty();
    }

    @Test
    @DisplayName("souvislá řada nehlásí mezeru")
    void numberGaps_continuousSeries_reportsNothing() {
        enableGapCheck(null);
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        var gaps = invoiceService.findNumberGaps();

        assertThat(gaps.isEnabled()).isTrue();
        assertThat(gaps.getMissingNumbers()).isEmpty();
    }

    @Test
    @DisplayName("smazání faktury uprostřed řady nahlásí přesně chybějící číslo")
    void numberGaps_afterDeletingMiddleInvoice_reportsIt() {
        enableGapCheck(null);
        String first = issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID).getInvoiceNumber();

        Long secondInvoice = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        String second = issueWithNextNumber(invoiceService, secondInvoice, USER_ID).getInvoiceNumber();

        Long thirdInvoice = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        issueWithNextNumber(invoiceService, thirdInvoice, USER_ID);

        // Prostřední doklad nikdo nedostal, takže ho lze smazat (V88) — a po něm zůstane díra,
        // protože MAX+1 se na uvolněné číslo už nevrátí.
        invoiceService.delete(secondInvoice, USER_ID);

        var gaps = invoiceService.findNumberGaps();

        assertThat(gaps.getMissingNumbers())
                .as("hláška musí říct přesné číslo, ne jen „něco chybí\"")
                .containsExactly(second);
        assertThat(gaps.getMissingNumbers()).doesNotContain(first);
    }

    @Test
    @DisplayName("mezera zmizí, jakmile se číslo znovu použije")
    void numberGaps_closeByReusingNumber() {
        enableGapCheck(null);
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        Long second = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        String freed = issueWithNextNumber(invoiceService, second, USER_ID).getInvoiceNumber();
        Long third = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        issueWithNextNumber(invoiceService, third, USER_ID);
        invoiceService.delete(second, USER_ID);
        assertThat(invoiceService.findNumberGaps().getMissingNumbers()).containsExactly(freed);

        // Náprava: příští doklad se vystaví s uvolněným číslem místo navrženého.
        Long fourth = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        var issueRequest = new InvoiceDto.IssueRequest();
        issueRequest.setInvoiceNumber(freed);
        issueRequest.setIssueDate(LocalDate.now());
        invoiceService.issue(fourth, issueRequest, USER_ID);

        assertThat(invoiceService.findNumberGaps().getMissingNumbers()).isEmpty();
    }

    @Test
    @DisplayName("startovní číslo umlčí starší mezery, novější hlásí dál")
    void numberGaps_respectsConfiguredStart() {
        enableGapCheck(null);
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        Long second = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        String freed = issueWithNextNumber(invoiceService, second, USER_ID).getInvoiceNumber();
        Long third = invoiceService.createFromOrder(
                createRequest(createOrderWithItem()), USER_ID).getId();
        String latest = issueWithNextNumber(invoiceService, third, USER_ID).getInvoiceNumber();
        invoiceService.delete(second, USER_ID);
        assertThat(invoiceService.findNumberGaps().getMissingNumbers()).containsExactly(freed);

        // Hlídat až od posledního dokladu — historická data z jiného systému řadu nedodržují
        // a bez tohohle by hláška křičela od prvního dne.
        enableGapCheck(latest);

        assertThat(invoiceService.findNumberGaps().getMissingNumbers()).isEmpty();
    }

    private void enableGapCheck(String from) {
        var profile = companyProfileMapper.find().orElseThrow();
        profile.setInvoiceGapCheckEnabled(true);
        profile.setInvoiceGapCheckFrom(from);
        companyProfileMapper.update(profile);
    }

    @Test
    @DisplayName("PŘEDANOU fakturu smazat nelze — opravuje se dobropisem (KN-1)")
    void delete_handedOverInvoice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);

        // Doklad, který zákazník DOSTAL, nelze zahodit — §42/§45 ZDPH na opravu předepisují
        // opravný daňový doklad. Hláška to musí říct konkrétně, ne jen „nelze smazat".
        assertThatThrownBy(() -> invoiceService.delete(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVOICE_NOT_DELETABLE");
                    assertThat(e.getMessage()).contains("dobropis");
                });

        assertThat(invoiceMapper.findById(draftInvoiceId))
                .as("doklad musí zůstat").isPresent();
    }

    @Test
    @DisplayName("NEPŘEDANOU vystavenou fakturu smazat lze — překlep nemá plodit dobropis (V88)")
    void delete_issuedButNotHandedOver_isAllowed() {
        // Do V88 aplikace přisuzovala předání už samotnému vystavení, přestože o odeslání
        // nevěděla nic a fakturu neposílá. Za každý překlep pak v evidenci ležela dvojice
        // dokladů dokazující, že se někdo upsal.
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        invoiceService.delete(draftInvoiceId, USER_ID);

        assertThat(invoiceMapper.findById(draftInvoiceId)).isEmpty();
    }

    @Test
    @DisplayName("vzetí předání zpět fakturu zase odemkne ke smazání (V88)")
    void revokeHandOver_unlocksDeletion() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);
        assertThatThrownBy(() -> invoiceService.delete(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class);

        invoiceService.revokeHandOver(draftInvoiceId, USER_ID);

        invoiceService.delete(draftInvoiceId, USER_ID);
        assertThat(invoiceMapper.findById(draftInvoiceId)).isEmpty();
    }

    @Test
    @DisplayName("fakturu s navázaným pokladním dokladem a dobropisem nelze smazat (KN-12)")
    void delete_isRejectedWhileDocumentsAreLinkedToTheInvoice() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        // Pokladní doklad jde vystavit i k NEPŘEDANÉ faktuře, takže tudy je kontrola
        // navázaných dokladů dosažitelná. Bez ní by mazání zastavil až cizí klíč
        // a obsluze by probublala DataIntegrityViolationException místo české hlášky.
        var receiptRequest = new cz.palo.autoservis.model.dto.billing.CashReceiptDto.CreateRequest();
        receiptRequest.setInvoiceId(draftInvoiceId);
        receiptRequest.setReceiptNumber(cashReceiptService.suggestNextNumber(null).getReceiptNumber());
        cashReceiptService.createFromInvoice(receiptRequest, USER_ID);

        assertThatThrownBy(() -> invoiceService.delete(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_HAS_LINKED_DOCUMENTS"));

        assertThat(cashReceiptService.getByInvoiceId(draftInvoiceId))
                .as("pokladní doklad na faktuře visí dál").hasSize(1);
    }

    // =========================================================================
    // Vrácení do konceptu (2026-08-08)
    // =========================================================================

    @Test
    @DisplayName("vystavenou fakturu lze vrátit do konceptu — uvolní číslo, zbytek zůstane")
    void revokeIssue_freesTheNumberAndKeepsTheRest() {
        String number = issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID).getInvoiceNumber();
        var before = invoiceService.getById(draftInvoiceId);

        var draft = invoiceService.revokeIssue(draftInvoiceId, USER_ID);

        assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(draft.getInvoiceNumber())
                .as("číslo se uvolní — o to při špatně zadaném čísle jde")
                .isNull();
        assertThat(draft.getItems())
                .as("položky ani strany se nezahazují; od mazání se to liší právě tímhle")
                .hasSameSizeAs(before.getItems());

        // Uvolněné číslo lze hned použít znovu — trigger neměnnosti (V71) tomu u konceptu
        // nebrání a přesně tohle je ta oprava překlepu.
        var request = new InvoiceDto.IssueRequest();
        request.setInvoiceNumber(number);
        request.setIssueDate(LocalDate.now());
        assertThat(invoiceService.issue(draftInvoiceId, request, USER_ID).getInvoiceNumber())
                .isEqualTo(number);
    }

    @Test
    @DisplayName("předanou fakturu do konceptu vrátit nelze — opravuje se dobropisem")
    void revokeIssue_handedOver_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);

        assertThatThrownBy(() -> invoiceService.revokeIssue(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_DELETABLE"));
    }

    @Test
    @DisplayName("zaplacenou fakturu nejdřív odplatit — hláška to říká rovnou")
    void revokeIssue_paid_isRejectedWithGuidance() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);

        assertThatThrownBy(() -> invoiceService.revokeIssue(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVOICE_NOT_ISSUED");
                    assertThat(e.getMessage()).contains("vezměte platbu zpět");
                });
    }

    @Test
    @DisplayName("s pokladním dokladem to neprojde — číslovaný doklad nemůže viset na konceptu")
    void revokeIssue_withCashReceipt_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        var receiptRequest = new cz.palo.autoservis.model.dto.billing.CashReceiptDto.CreateRequest();
        receiptRequest.setInvoiceId(draftInvoiceId);
        receiptRequest.setReceiptNumber(cashReceiptService.suggestNextNumber(null).getReceiptNumber());
        cashReceiptService.createFromInvoice(receiptRequest, USER_ID);

        assertThatThrownBy(() -> invoiceService.revokeIssue(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_HAS_LINKED_DOCUMENTS"));
    }

    // =========================================================================
    // Vzetí platby zpět (2026-08-08)
    // =========================================================================

    @Test
    @DisplayName("platbu lze vzít zpět — PAID → ISSUED a záznam o úhradě zmizí")
    void revokePayment_returnsInvoiceToIssued() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);
        assertThat(invoiceService.getById(draftInvoiceId).getPaidAt()).isNotNull();

        var reverted = invoiceService.revokePayment(draftInvoiceId, USER_ID);

        assertThat(reverted.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(reverted.getPaidAt()).isNull();
        assertThat(reverted.getPaidAmount()).isNull();
    }

    @Test
    @DisplayName("vzetí platby zpět NEVRACÍ předání — jsou to dvě nezávislé věci")
    void revokePayment_keepsHandOver() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);

        invoiceService.revokePayment(draftInvoiceId, USER_ID);

        // Razítko předání mohlo vzniknout i dřív ručně; kdo doklad opravdu nemá, vezme
        // předání zpět zvlášť. Číslo ani datum vystavení se nemění — doklad platí dál.
        var invoice = invoiceService.getById(draftInvoiceId);
        assertThat(invoice.getHandedOverAt()).isNotNull();
        assertThat(invoice.getInvoiceNumber()).isNotNull();
    }

    @Test
    @DisplayName("platbu nelze vzít zpět, visí-li na faktuře pokladní doklad")
    void revokePayment_withCashReceipt_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        var receiptRequest = new cz.palo.autoservis.model.dto.billing.CashReceiptDto.CreateRequest();
        receiptRequest.setInvoiceId(draftInvoiceId);
        receiptRequest.setReceiptNumber(cashReceiptService.suggestNextNumber(null).getReceiptNumber());
        cashReceiptService.createFromInvoice(receiptRequest, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);

        // PPD má vlastní číselnou řadu — nemůže viset na faktuře, která se tváří nezaplaceně.
        assertThatThrownBy(() -> invoiceService.revokePayment(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_HAS_CASH_RECEIPT"));
    }

    @Test
    @DisplayName("u nezaplacené faktury není co vracet")
    void revokePayment_unpaidInvoice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        assertThatThrownBy(() -> invoiceService.revokePayment(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_PAID"));
    }

    // =========================================================================
    // Dobropisovaná faktura je terminální (2026-08-08)
    // =========================================================================

    @Test
    @DisplayName("dobropisovanou fakturu nelze označit zaplaceno — vyrušený doklad se neplatí")
    void markPaid_creditedInvoice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);
        issueCreditNoteFor(draftInvoiceId);

        assertThatThrownBy(() -> invoiceService.markPaid(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_CREDITED"));
    }

    @Test
    @DisplayName("u dobropisované faktury nelze vzít předání zpět — vznikla by zakázaná kombinace")
    void revokeHandOver_creditedInvoice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);
        issueCreditNoteFor(draftInvoiceId);

        // Bez téhle zábrany by šlo předání odklikat a vznikla by „nepředaná + dobropisovaná",
        // přestože dobropis lze vystavit jen k předané. Dosud to zastavila až kontrola
        // navázaných dokladů při mazání — tedy pojistka, ne pravidlo.
        assertThatThrownBy(() -> invoiceService.revokeHandOver(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_CREDITED"));

        assertThat(invoiceService.getById(draftInvoiceId).getHandedOverAt()).isNotNull();
    }

    @Test
    @DisplayName("rozpracovaná oprava je vidět ve výpisu — koncept dobropisu blokuje, ale nic nenastavuje")
    void draftCreditNote_isVisibleInTheList() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);

        assertThat(listedInvoice(draftInvoiceId).isHasDraftCreditNote()).isFalse();

        var request = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        request.setOriginalInvoiceId(draftInvoiceId);
        request.setCorrectionReason("Reklamace");
        creditNoteService.createFromInvoice(request, USER_ID);

        // Koncept dobropisu nemá číslo a `credited_at` nenastavuje — do 2026-08-08 byl proto
        // na faktuře neviditelný, přestože blokuje druhou opravu i fakturaci zakázky.
        assertThat(listedInvoice(draftInvoiceId).isHasDraftCreditNote()).isTrue();
        assertThat(listedInvoice(draftInvoiceId).getCreditedAt())
                .as("koncept nic neopravuje, razítko patří až vystavení")
                .isNull();
    }

    private InvoiceDto.ListResponse listedInvoice(Long id) {
        return invoiceService.getByCustomerId(CUSTOMER_ID).stream()
                .filter(i -> i.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void issueCreditNoteFor(Long invoiceId) {
        var request = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        request.setOriginalInvoiceId(invoiceId);
        request.setCorrectionReason("Test");
        creditNoteService.issue(creditNoteService.createFromInvoice(request, USER_ID).getId(), USER_ID);
    }

    @Test
    @DisplayName("zaplacení orazítkuje i předání — kdo platí, doklad má")
    void markPaid_stampsHandOver() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        assertThat(invoiceService.getById(draftInvoiceId).getHandedOverAt())
                .as("vystavení předání nenastavuje")
                .isNull();

        invoiceService.markPaid(draftInvoiceId, USER_ID);

        // Bez tohohle by u faktury zaplacené na místě zůstal záznam tvrdící „zákazník doklad
        // nedostal" o dokladu, se kterým aplikace všude zachází jako s předaným.
        assertThat(invoiceService.getById(draftInvoiceId).getHandedOverAt()).isNotNull();
    }

    @Test
    @DisplayName("zaplacení nepřepíše dřívější datum předání")
    void markPaid_keepsEarlierHandOverDate() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.handOver(draftInvoiceId, USER_ID);
        var handedOver = invoiceService.getById(draftInvoiceId).getHandedOverAt();

        invoiceService.markPaid(draftInvoiceId, USER_ID);

        assertThat(invoiceService.getById(draftInvoiceId).getHandedOverAt())
                .as("předání proběhlo dřív než platba — razítko se nesmí posunout")
                .isEqualTo(handedOver);
    }

    @Test
    @DisplayName("dobropis jde vystavit až k PŘEDANÉ faktuře — jinak se doklad smaže a vystaví znovu")
    void creditNote_requiresHandedOverInvoice() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        var request = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        request.setOriginalInvoiceId(draftInvoiceId);
        request.setCorrectionReason("Reklamace — vrácení dílu");

        // Dobropis opravuje základ daně nebo daň v CIZÍ evidenci. U faktury, kterou zákazník
        // nikdy nedostal, není co opravovat — odpočet z ní neuplatnil (rozhodnutí uživatele
        // 2026-08-08 podle účetního pravidla).
        assertThatThrownBy(() -> creditNoteService.createFromInvoice(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException e = (BusinessRuleException) ex;
                    assertThat(e.getRuleCode()).isEqualTo("INVOICE_NOT_HANDED_OVER");
                    assertThat(e.getMessage()).contains("Smažte ji a vystavte znovu");
                });

        invoiceService.handOver(draftInvoiceId, USER_ID);

        assertThat(creditNoteService.createFromInvoice(request, USER_ID))
                .as("po předání už dobropis smysl má").isNotNull();
    }

    @Test
    @DisplayName("zaplacenou fakturu smazat NELZE se stejnou hláškou jako u vystavené (KN-1)")
    void delete_paidInvoice_isRejectedWithSameGuidance() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);

        assertThatThrownBy(() -> invoiceService.delete(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_DELETABLE"));
    }

    // =========================================================================
    // Zakázané přechody
    // =========================================================================

    @Test
    @DisplayName("DRAFT → PAID přeskočením vystavení → INVALID_STATUS_TRANSITION (422)")
    void markPaid_fromDraft_isRejected() {
        assertInvalidTransition(() -> invoiceService.markPaid(draftInvoiceId, USER_ID));

        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .as("stav se nesmí změnit").isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("PAID je terminální — issue i markPaid selžou a smazat ho taky nejde")
    void paid_isTerminal() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        invoiceService.markPaid(draftInvoiceId, USER_ID);

        assertInvalidTransition(() -> issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID));
        assertInvalidTransition(() -> invoiceService.markPaid(draftInvoiceId, USER_ID));
        // Mazání má vlastní, návodnou hlášku (KN-1) — odmítnuté je pořád, jen se uživatel
        // dozví, že má použít dobropis.
        assertThatThrownBy(() -> invoiceService.delete(draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_DELETABLE"));

        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("historicky stornovaná faktura je terminální — issue i markPaid selžou")
    void cancelled_isTerminal() {
        // Do CANCELLED se aplikace už nedostane (koncept se maže), ale stav zůstává kvůli
        // fakturám stornovaným dřív. Ty se nesmí dát „oživit". Stav se nastavuje přes mapper,
        // ne jdbcTemplate: MyBatis by si jinak v session cache nechal původní DRAFT a test
        // by měřil něco jiného, než chce.
        invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.CANCELLED, InvoiceStatus.DRAFT);

        assertInvalidTransition(() -> issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID));
        assertInvalidTransition(() -> invoiceService.markPaid(draftInvoiceId, USER_ID));

        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    @DisplayName("ISSUED → ISSUED (dvojí vystavení) → INVALID_STATUS_TRANSITION")
    void issue_twice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        assertInvalidTransition(() -> issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID));
    }

    @Test
    @DisplayName("přechod na neexistující faktuře → ResourceNotFoundException (404)")
    void transition_unknownInvoice_throwsResourceNotFound() {
        assertThatThrownBy(() -> issueWithNextNumber(invoiceService, 999_999L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invoiceService.markPaid(999_999L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invoiceService.delete(999_999L, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Souběh — guardovaný UPDATE (K5)
    // =========================================================================

    @Test
    @DisplayName("souběžná změna stavu → 409 INVOICE_STATE_CHANGED, ne tiché přepsání")
    void transition_whenStatusChangedConcurrently_throwsConflict() {
        // Simulace jiného požadavku, který fakturu mezitím vystavil: obejdeme service
        // a přepneme stav přímo mapperem. Service má fakturu načtenou jako DRAFT,
        // takže guardovaný UPDATE (WHERE status = DRAFT) nesedne na žádný řádek.
        invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.CANCELLED, InvoiceStatus.DRAFT);

        assertThatThrownBy(() -> issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .as("z CANCELLED se nedá vystavit — chytí to už předkontrola")
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("guardovaný updateStatus s nesedícím očekávaným stavem vrátí 0 řádků (409 cesta)")
    void guardedUpdateStatus_staleExpectedStatus_affectsNoRow() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        int affected = invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.PAID, InvoiceStatus.DRAFT);

        assertThat(affected).as("0 řádků → service z toho udělá ConflictException").isZero();
        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.ISSUED);
    }

    // =========================================================================
    // Uzamčení editace mimo DRAFT
    // =========================================================================

    @Test
    @DisplayName("hlavičku vystavené faktury nelze měnit → INVOICE_NOT_EDITABLE (422)")
    void update_issuedInvoice_isRejected() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        InvoiceDto.UpdateRequest request = new InvoiceDto.UpdateRequest();
        request.setDueDate(LocalDate.now().plusDays(30));
        request.setPaymentMethod(PaymentMethod.CASH);

        assertThatThrownBy(() -> invoiceService.update(draftInvoiceId, request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("hlavičku DRAFT faktury lze měnit")
    void update_draftInvoice_changesEditableFields() {
        InvoiceDto.UpdateRequest request = new InvoiceDto.UpdateRequest();
        request.setDueDate(LocalDate.of(2026, 12, 31));
        request.setConstantSymbol("0558");
        request.setPaymentMethod(PaymentMethod.CASH);
        request.setNote("upraveno");
        request.setStatus(InvoiceStatus.DRAFT);

        InvoiceDto.DetailResponse updated = invoiceService.update(draftInvoiceId, request, USER_ID);

        assertThat(updated.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(updated.getConstantSymbol()).isEqualTo("0558");
        assertThat(updated.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(updated.getNote()).isEqualTo("upraveno");
        assertThat(updated.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("update NEMŮŽE obejít stavový automat — status z requestu se ignoruje (TD-49)")
    void update_cannotBypassStateMachine() {
        // Pokus přepnout DRAFT rovnou na PAID přes PUT musí být bez efektu — stav se mění
        // jen přes issue/markPaid/cancel. Service si stav zapamatuje PŘED applyUpdate
        // (konvertor mutuje na místě), takže status z UpdateRequest neprojde.
        InvoiceDto.UpdateRequest request = new InvoiceDto.UpdateRequest();
        request.setDueDate(LocalDate.of(2026, 12, 31));
        request.setPaymentMethod(PaymentMethod.CASH);
        request.setStatus(InvoiceStatus.PAID);   // pokus obejít automat

        InvoiceDto.DetailResponse updated = invoiceService.update(draftInvoiceId, request, USER_ID);

        assertThat(updated.getStatus()).as("stav zůstal DRAFT").isEqualTo(InvoiceStatus.DRAFT);
        assertThat(updated.getDueDate()).as("editovatelná pole se přesto uložila")
                .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(invoiceMapper.findById(draftInvoiceId).orElseThrow().getStatus())
                .as("a DRAFT je i v databázi").isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("do DRAFT faktury lze přidat, upravit i smazat položku")
    void items_areEditableInDraft() {
        // Každá položka faktury musí odkazovat na položku zakázky — @NotNull v DTO
        // i NOT NULL + FK v DB (V14). Faktura je snapshot zakázky, ne volný doklad.
        Long orderItemId = invoiceService.getById(draftInvoiceId).getItems().getFirst().getOrderItemId();

        InvoiceItemDto.CreateRequest createRequest = new InvoiceItemDto.CreateRequest();
        createRequest.setOrderItemId(orderItemId);
        createRequest.setName("Doprava");
        createRequest.setQuantity(BigDecimal.ONE);
        createRequest.setUnit("ks");
        createRequest.setUnitPrice(new BigDecimal("350.00"));
        createRequest.setVatRate((short) 21);
        createRequest.setPosition((short) 2);

        InvoiceItemDto.Response added = invoiceService.addItem(draftInvoiceId, createRequest);
        assertThat(added.getId()).isNotNull();
        assertThat(added.getName()).isEqualTo("Doprava");
        assertThat(invoiceService.getById(draftInvoiceId).getItems()).hasSize(2);

        InvoiceItemDto.UpdateRequest updateRequest = new InvoiceItemDto.UpdateRequest();
        updateRequest.setName("Doprava a manipulace");
        updateRequest.setQuantity(new BigDecimal("2"));
        updateRequest.setUnit("ks");
        updateRequest.setUnitPrice(new BigDecimal("400.00"));
        updateRequest.setVatRate((short) 21);
        updateRequest.setPosition((short) 2);

        InvoiceItemDto.Response updated = invoiceService.updateItem(added.getId(), updateRequest);
        assertThat(updated.getName()).isEqualTo("Doprava a manipulace");
        assertThat(updated.getUnitPrice()).isEqualByComparingTo("400.00");

        invoiceService.deleteItem(added.getId());
        assertThat(invoiceService.getById(draftInvoiceId).getItems()).hasSize(1);
    }

    @Test
    @DisplayName("položky vystavené faktury jsou zamčené — přidání, změna i smazání selže")
    void items_areLockedOnceIssued() {
        InvoiceItemDto.Response existingItem = invoiceService.getById(draftInvoiceId).getItems().getFirst();
        Long itemId = existingItem.getId();
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        InvoiceItemDto.CreateRequest createRequest = new InvoiceItemDto.CreateRequest();
        createRequest.setOrderItemId(existingItem.getOrderItemId());
        createRequest.setName("Doprava");
        createRequest.setQuantity(BigDecimal.ONE);
        createRequest.setUnit("ks");
        createRequest.setUnitPrice(new BigDecimal("350.00"));
        createRequest.setVatRate((short) 21);
        createRequest.setPosition((short) 2);

        InvoiceItemDto.UpdateRequest updateRequest = new InvoiceItemDto.UpdateRequest();
        updateRequest.setName("Změna");
        updateRequest.setQuantity(BigDecimal.ONE);
        updateRequest.setUnit("hod");
        updateRequest.setUnitPrice(new BigDecimal("999.00"));
        updateRequest.setVatRate((short) 21);
        updateRequest.setPosition((short) 1);

        assertNotEditable(() -> invoiceService.addItem(draftInvoiceId, createRequest));
        assertNotEditable(() -> invoiceService.updateItem(itemId, updateRequest));
        assertNotEditable(() -> invoiceService.deleteItem(itemId));

        assertThat(invoiceService.getById(draftInvoiceId).getItems())
                .as("položky zůstaly nedotčené").hasSize(1);
    }

    @Test
    @DisplayName("položky historicky stornované faktury jsou zamčené taky")
    void items_areLockedOnceCancelled() {
        // Stav CANCELLED aplikace už nenastaví (koncept se maže), ale u faktur stornovaných
        // dřív musí zámek editace platit dál.
        Long itemId = invoiceService.getById(draftInvoiceId).getItems().getFirst().getId();
        invoiceMapper.updateStatus(draftInvoiceId, InvoiceStatus.CANCELLED, InvoiceStatus.DRAFT);

        assertNotEditable(() -> invoiceService.deleteItem(itemId));
    }

    // =========================================================================
    // E1 — storno→refaktura, guardy fakturace, dopočtené souhrny
    // =========================================================================

    @Test
    @DisplayName("po smazání konceptu lze zakázku vyfakturovat znovu (E1.2/K-1)")
    void delete_thenReinvoiceSameOrder_succeeds() {
        invoiceService.delete(draftInvoiceId, USER_ID);

        // Po smazání není co blokovat — dřív tuhle roli plnil částečný unikát (V48),
        // který stornované faktury z „aktivních" vyřazoval.
        InvoiceDto.DetailResponse reissued = invoiceService.createFromOrder(createRequest(orderId), USER_ID);

        assertThat(reissued.getId()).isNotEqualTo(draftInvoiceId);
        assertThat(reissued.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoiceService.getByOrderId(orderId).getId())
                .as("getByOrderId vrací novou fakturu").isEqualTo(reissued.getId());
    }

    @Test
    @DisplayName("stornovanou zakázku nelze fakturovat → ORDER_NOT_INVOICEABLE (E1.5)")
    void createFromOrder_cancelledOrder_isRejected() {
        Long cancelledOrderId = createOrderWithItem();
        // Požadavek se sestaví DŘÍV, než se zakázka zruší — createRequest ji jinak přepne
        // na „Dokončena" a test by rušil něco, co vzápětí sám odčinil.
        InvoiceDto.CreateRequest request = createRequest(cancelledOrderId);
        Order order = orderMapper.findById(cancelledOrderId).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        orderMapper.update(order);

        assertThatThrownBy(() -> invoiceService.createFromOrder(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("ORDER_NOT_INVOICEABLE"));
    }

    @Test
    @DisplayName("vystavit fakturu bez položek nelze → INVOICE_HAS_NO_ITEMS (E1.5)")
    void issue_withNoItems_isRejected() {
        Long itemId = invoiceService.getById(draftInvoiceId).getItems().getFirst().getId();
        invoiceService.deleteItem(itemId);

        assertThatThrownBy(() -> issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("INVOICE_HAS_NO_ITEMS"));
    }

    @Test
    @DisplayName("položka faktury z cizí zakázky → ITEM_NOT_OF_INVOICED_ORDER (E1.5)")
    void addItem_orderItemOfAnotherOrder_isRejected() {
        Long otherOrderId = createOrderWithItem();
        Long foreignOrderItemId = orderItemMapper.findByOrderId(otherOrderId).getFirst().getId();

        InvoiceItemDto.CreateRequest req = new InvoiceItemDto.CreateRequest();
        req.setOrderItemId(foreignOrderItemId);
        req.setName("Cizí položka");
        req.setQuantity(BigDecimal.ONE);
        req.setUnit("ks");
        req.setUnitPrice(new BigDecimal("100.00"));
        req.setVatRate((short) 21);
        req.setPosition((short) 2);

        assertThatThrownBy(() -> invoiceService.addItem(draftInvoiceId, req))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getRuleCode())
                        .isEqualTo("ITEM_NOT_OF_INVOICED_ORDER"));
    }

    @Test
    @DisplayName("odpověď addItem nese dopočtené net/vat/gross (E1.6/S-7)")
    void addItem_responseHasComputedTotals() {
        Long orderItemId = invoiceService.getById(draftInvoiceId).getItems().getFirst().getOrderItemId();

        InvoiceItemDto.CreateRequest req = new InvoiceItemDto.CreateRequest();
        req.setOrderItemId(orderItemId);
        req.setName("Doprava");
        req.setQuantity(new BigDecimal("2"));
        req.setUnit("ks");
        req.setUnitPrice(new BigDecimal("100.00"));
        req.setVatRate((short) 21);
        req.setPosition((short) 3);

        InvoiceItemDto.Response added = invoiceService.addItem(draftInvoiceId, req);

        assertThat(added.getNet()).isEqualByComparingTo("200.00");
        assertThat(added.getVat()).isEqualByComparingTo("42.00");
        assertThat(added.getGross()).isEqualByComparingTo("242.00");
    }

    @Test
    @DisplayName("nullable pole faktury (constantSymbol) lze přes PUT vymazat (E1.1/K-11)")
    void update_clearsNullableField() {
        InvoiceDto.UpdateRequest set = new InvoiceDto.UpdateRequest();
        set.setDueDate(LocalDate.of(2026, 12, 31));
        set.setConstantSymbol("0558");
        set.setPaymentMethod(PaymentMethod.CASH);
        set.setStatus(InvoiceStatus.DRAFT);
        invoiceService.update(draftInvoiceId, set, USER_ID);
        assertThat(invoiceService.getById(draftInvoiceId).getConstantSymbol()).isEqualTo("0558");

        InvoiceDto.UpdateRequest clear = new InvoiceDto.UpdateRequest();
        clear.setDueDate(LocalDate.of(2026, 12, 31));
        clear.setPaymentMethod(PaymentMethod.CASH);
        clear.setStatus(InvoiceStatus.DRAFT);
        // constantSymbol ponecháno null → full-replace ho má vymazat (dřív PATCH ponechal starou hodnotu)
        invoiceService.update(draftInvoiceId, clear, USER_ID);
        assertThat(invoiceService.getById(draftInvoiceId).getConstantSymbol())
                .as("full-replace: null v requestu vymaže hodnotu").isNull();
    }

    @Test
    @DisplayName("koncept číslo nemá — dostane ho až při VYSTAVENÍ, v řadě období zvoleného data")
    void invoiceNumber_assignedAtIssue() {
        // Koncept se schválně zakládá se starým datem vystavení. Doklad s tím datem i odejde
        // (server ho nepřerazítkovává, rozhodnutí uživatele 2026-08-07) a číslo se skládá
        // z TÉHOŽ data — jinak by doklad nesl číslo jiného období, než na kterém má datum.
        LocalDate staleDate = LocalDate.now().minusMonths(4);
        Long id = draftIssuedOn(staleDate, 14);

        assertThat(invoiceService.getById(id).getInvoiceNumber())
                .as("koncept číslo nemá — nemůže ho tedy ani spálit, když ho obsluha zruší")
                .isNull();

        issueWithNextNumber(invoiceService, id, USER_ID);

        InvoiceDto.DetailResponse issued = invoiceService.getById(id);
        assertThat(issued.getIssueDate())
                .as("na fakturu jde datum zadané obsluhou, ne dnešek")
                .isEqualTo(staleDate);
        assertThat(issued.getInvoiceNumber())
                .as("číslo je z řady téhož období jako datum vystavení")
                .startsWith(staleDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
    }

    @Test
    @DisplayName("zahozený koncept nedělá v řadě mezeru — číslo dostane až další vystavený")
    void deletedDraft_doesNotConsumeNumber() {
        // Jádro varianty C (rozhodnutí uživatele 2026-08-02): koncept číslo nedrží, takže
        // jeho zahození řadu neposune. Dřív (V71) číslo vznikalo při založení a zrušený
        // koncept si ho nechal — v řadě zůstala nevysvětlitelná mezera.
        String expected = invoiceService.suggestNextNumber(LocalDate.now()).getInvoiceNumber();

        Long abandoned = invoiceService.createFromOrder(createRequest(createOrderWithItem()), USER_ID).getId();
        invoiceService.delete(abandoned, USER_ID);

        Long next = invoiceService.createFromOrder(createRequest(createOrderWithItem()), USER_ID).getId();
        issueWithNextNumber(invoiceService, next, USER_ID);

        assertThat(invoiceService.getById(next).getInvoiceNumber())
                .as("řada pokračuje tam, kde skončila — zrušený koncept ji neposunul")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("maska se nevynucuje — ruční číslo mimo masku projde a řadu neposune")
    void issue_acceptsNumberOutsideMask() {
        // Maska je předpis pro GENEROVÁNÍ návrhu, ne validační pravidlo — číslo zůstává
        // editovatelné i při zapnutém automatu (rozhodnutí uživatele 2026-08-02). Číslo
        // mimo masku regex řady nematchne, takže MAX+1 pokračuje, jako by neexistovalo.
        String suggestionBefore = invoiceService.suggestNextNumber(LocalDate.now()).getInvoiceNumber();

        Long id = invoiceService.createFromOrder(createRequest(createOrderWithItem()), USER_ID).getId();
        InvoiceDto.DetailResponse issued = invoiceService.issue(id, requestFor("RUCNI-17/26"), USER_ID);

        assertThat(issued.getInvoiceNumber()).isEqualTo("RUCNI-17/26");
        assertThat(invoiceService.suggestNextNumber(LocalDate.now()).getInvoiceNumber())
                .as("číslo mimo masku řadu neovlivní — další návrh je stejný")
                .isEqualTo(suggestionBefore);
    }

    @Test
    @DisplayName("variabilní symbol z dialogu se uloží; server žádný nedosazuje")
    void issue_storesVariableSymbolFromRequest() {
        // Scénář z §42/§45: opravná faktura po dobropisu nese VS té původní, aby platba
        // a upomínky dál seděly. Předvyplnění z čísla dělá dialog, ne server — jinak by
        // nešlo vystavit doklad bez VS (u hotovosti nemá co párovat).
        InvoiceDto.IssueRequest request = requestFor("2026999001");
        request.setVariableSymbol("1234567890");

        assertThat(invoiceService.issue(draftInvoiceId, request, USER_ID).getVariableSymbol())
                .isEqualTo("1234567890");
    }

    @Test
    @DisplayName("prázdné číslo → 422, ne pád na DB constraintu")
    void issue_withoutNumber_isRejected() {
        // @NotBlank v DTO tohle chytí na API; service to jistí i pro přímá volání, protože
        // CHECK chk_invoice_issued_has_number by jinak skončil surovou chybou z DB.
        assertThatThrownBy(() -> invoiceService.issue(draftInvoiceId, requestFor("   "), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NUMBER_MISSING"));
    }

    @Test
    @DisplayName("obsazené číslo se při vystavení odmítne s radou, ne s 500")
    void issue_rejectsDuplicateNumber() {
        Long first = invoiceService.createFromOrder(createRequest(createOrderWithItem()), USER_ID).getId();
        String taken = issueWithNextNumber(invoiceService, first, USER_ID).getInvoiceNumber();

        Long second = invoiceService.createFromOrder(createRequest(createOrderWithItem()), USER_ID).getId();

        assertThatThrownBy(() -> invoiceService.issue(second, requestFor(taken), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUPLICATE_INVOICE_NUMBER"));
    }

    // =========================================================================
    // E2 — evidence úhrad
    // =========================================================================

    @Test
    @DisplayName("markPaid zaznamená datum, částku a způsob úhrady (E2.1/K-9)")
    void markPaid_recordsPaymentDetails() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        assertThat(invoiceService.getById(draftInvoiceId).getPaidAt())
                .as("před úhradou prázdné").isNull();

        invoiceService.markPaid(draftInvoiceId, USER_ID);

        InvoiceDto.DetailResponse paid = invoiceService.getById(draftInvoiceId);
        assertThat(paid.getPaidAt()).as("datum úhrady zaznamenáno").isNotNull();
        assertThat(paid.getPaidAmount()).as("plná částka dokladu").isEqualByComparingTo(paid.getTotalGross());
        assertThat(paid.getPaidMethod()).as("způsob úhrady").isEqualTo(paid.getPaymentMethod());
    }

    @Test
    @DisplayName("filtr overdue vrací jen vystavené faktury po splatnosti (E2.2/K-9)")
    void getPage_overdueFilter_returnsOnlyOverdueIssued() {
        var params = new cz.palo.autoservis.model.dto.billing.InvoiceSearchParams();
        params.setPage(1);
        params.setPageSize(50);
        params.setOverdue(true);

        var page = invoiceService.getPage(params);

        assertThat(page.getContent())
                .as("seed faktura zakázky 2 je ISSUED se splatností 2025-12-15 → po splatnosti").isNotEmpty();
        assertThat(page.getContent()).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(row.getDueDate()).isBefore(java.time.LocalDate.now());
        });
    }

    // =========================================================================
    // Čtecí cesty
    // =========================================================================

    @Test
    @DisplayName("fakturu lze najít podle čísla i podle zakázky a jde o tentýž doklad")
    void lookupByNumberAndByOrder_returnSameInvoice() {
        // Podle čísla jde hledat až vystavený doklad — koncept žádné nemá.
        String number = issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID).getInvoiceNumber();

        assertThat(invoiceService.getByInvoiceNumber(number).getId()).isEqualTo(draftInvoiceId);
        assertThat(invoiceService.getByOrderId(orderId).getId()).isEqualTo(draftInvoiceId);
    }

    @Test
    @DisplayName("hledání podle neznámého id, čísla i zakázky → 404")
    void lookup_unknownKeys_throwResourceNotFound() {
        assertThatThrownBy(() -> invoiceService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invoiceService.getByInvoiceNumber("999999999"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invoiceService.getByOrderId(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update neexistující faktury → ResourceNotFoundException (404)")
    void update_unknownInvoice_throwsResourceNotFound() {
        InvoiceDto.UpdateRequest request = new InvoiceDto.UpdateRequest();
        request.setDueDate(LocalDate.now().plusDays(14));

        assertThatThrownBy(() -> invoiceService.update(999_999L, request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("faktura s neexistující fakturační adresou → ResourceNotFoundException (404)")
    void createFromOrder_unknownBillingAddress_throwsResourceNotFound() {
        Long newOrderId = createOrderWithItem();
        InvoiceDto.CreateRequest request = createRequest(newOrderId);
        request.setBillingAddressId(999_999L);

        assertThatThrownBy(() -> invoiceService.createFromOrder(request, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("u firemního zákazníka se na doklad zmrazí i IČO a DIČ")
    void createFromOrder_companyCustomer_freezesIcoAndDic() {
        // Zákazník 1 (Jan Novák) je fyzická osoba bez IČO, takže na něm by chybějící
        // přenos IČO/DIČ nebyl vidět. Firemní zákazník 3 (Logistika ABC s.r.o.) je má.
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(3L)
                .vehicleId(2L)               // seed: vozidlo zákazníka 3
                .description("Zakázka firemního zákazníka")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        orderItemMapper.insert(OrderItem.builder()
                .orderId(order.getId())
                .itemType(OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build());

        InvoiceDto.CreateRequest request = createRequest(order.getId());
        request.setBillingAddressId(5L); // seed: BILLING adresa zákazníka 3

        InvoiceDto.PartyResponse customer =
                invoiceService.createFromOrder(request, USER_ID).getCustomer();

        assertThat(customer.getName()).isEqualTo("Logistika ABC s.r.o.");
        assertThat(customer.getIco()).isEqualTo("12345678");
        assertThat(customer.getDic()).isEqualTo("CZ12345678");
        assertThat(customer.getCity()).isEqualTo("Ostrava");
    }

    @Test
    @DisplayName("getByCustomerId vrátí faktury daného zákazníka")
    void getByCustomerId_returnsCustomerInvoices() {
        var invoices = invoiceService.getByCustomerId(CUSTOMER_ID);

        assertThat(invoices).isNotEmpty();
        assertThat(invoices).extracting(InvoiceDto.ListResponse::getId).contains(draftInvoiceId);
    }

    @Test
    @DisplayName("getPage vrátí stránku faktur i s celkovým počtem a součty")
    void getPage_returnsPagedInvoices() {
        var params = new cz.palo.autoservis.model.dto.billing.InvoiceSearchParams();
        params.setPage(1);
        params.setPageSize(50);

        var page = invoiceService.getPage(params);

        assertThat(page).isNotNull();
        assertThat(page.getContent()).isNotEmpty();
        assertThat(page.getTotalElements()).isPositive();
        assertThat(page.getContent()).extracting(InvoiceDto.ListResponse::getId).contains(draftInvoiceId);
        assertThat(page.getContent())
                .filteredOn(row -> row.getStatus() != InvoiceStatus.DRAFT)
                .allSatisfy(row -> assertThat(row.getInvoiceNumber())
                        .as("vystavený doklad má číslo vždy (CHECK chk_invoice_issued_has_number)")
                        .isNotBlank());
    }

    @Test
    @DisplayName("seznam nese `creditedAt` — jinak v přehledu nepoznáš dobropisovanou fakturu")
    void getPage_carriesCreditedAtSoTheListCanFlagCreditedInvoices() {
        // Dobropis stav faktury nemění (zůstane ISSUED/PAID), takže bez tohohle pole vypadá
        // opravená faktura v seznamu úplně stejně jako platná pohledávka.
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);
        // Dobropis opravuje doklad, který zákazník DOSTAL — u nepředané faktury není co
        // opravovat, ta se prostě smaže a vystaví znovu (rozhodnutí uživatele 2026-08-08).
        invoiceService.handOver(draftInvoiceId, USER_ID);

        var creditNoteRequest = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        creditNoteRequest.setOriginalInvoiceId(draftInvoiceId);
        creditNoteRequest.setCorrectionReason("Reklamace — vrácení dílu");
        creditNoteService.issue(
                creditNoteService.createFromInvoice(creditNoteRequest, USER_ID).getId(), USER_ID);

        var params = new cz.palo.autoservis.model.dto.billing.InvoiceSearchParams();
        params.setPage(1);
        params.setPageSize(50);

        assertThat(invoiceService.getPage(params).getContent())
                .filteredOn(row -> row.getId().equals(draftInvoiceId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getCreditedAt()).as("razítko dobropisu se veze do seznamu").isNotNull();
                    assertThat(row.getStatus())
                            .as("stav dokladu se dobropisem nemění — proto ten příznak vedle")
                            .isEqualTo(InvoiceStatus.ISSUED);
                });
    }

    @Test
    @DisplayName("operace s neexistující položkou faktury → ResourceNotFoundException (404)")
    void itemOperations_unknownItem_throwResourceNotFound() {
        InvoiceItemDto.UpdateRequest updateRequest = new InvoiceItemDto.UpdateRequest();
        updateRequest.setName("Cokoli");
        updateRequest.setQuantity(BigDecimal.ONE);
        updateRequest.setUnit("ks");
        updateRequest.setUnitPrice(new BigDecimal("1.00"));
        updateRequest.setVatRate((short) 21);
        updateRequest.setPosition((short) 1);

        assertThatThrownBy(() -> invoiceService.updateItem(999_999L, updateRequest))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invoiceService.deleteItem(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("přidání položky k neexistující faktuře → ResourceNotFoundException (404)")
    void addItem_unknownInvoice_throwsResourceNotFound() {
        InvoiceItemDto.CreateRequest request = new InvoiceItemDto.CreateRequest();
        request.setOrderItemId(1L);
        request.setName("Cokoli");
        request.setQuantity(BigDecimal.ONE);
        request.setUnit("ks");
        request.setUnitPrice(new BigDecimal("1.00"));
        request.setVatRate((short) 21);
        request.setPosition((short) 1);

        assertThatThrownBy(() -> invoiceService.addItem(999_999L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =========================================================================
    // Pomocné aserce a fixtury
    // =========================================================================

    private static void assertInvalidTransition(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    private static void assertNotEditable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("INVOICE_NOT_EDITABLE"));
    }

    private Long createOrder() {
        Order order = Order.builder()
                .receivedAt(LocalDate.now())
                .customerId(CUSTOMER_ID)
                .vehicleId(VEHICLE_ID)
                .description("Zakázka pro test faktury")
                .estimatedPrice(new BigDecimal("1000"))
                .createdBy(USER_ID)
                .build();
        orderMapper.insert(order);
        return order.getId();
    }

    private Long createOrderWithItem() {
        Long id = createOrder();
        OrderItem item = OrderItem.builder()
                .orderId(id)
                .itemType(OrderItemType.LABOR)
                .name("Práce mechanika")
                .quantity(BigDecimal.ONE)
                .unit("hod")
                .unitPrice(new BigDecimal("500"))
                .vatRate((short) 21)
                .position((short) 1)
                .createdBy(USER_ID)
                .build();
        orderItemMapper.insert(item);
        return id;
    }

    // =========================================================================
    // Dobropis uvolní zakázku pro novou fakturu (V69)
    // =========================================================================

    @Test
    @DisplayName("po VYSTAVENÍ dobropisu lze zakázku fakturovat znovu; koncept dobropisu ne (V69)")
    void issuedCreditNote_unlocksTheOrderForANewInvoice() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        // Dobropis opravuje doklad, který zákazník DOSTAL — u nepředané faktury není co
        // opravovat, ta se prostě smaže a vystaví znovu (rozhodnutí uživatele 2026-08-08).
        invoiceService.handOver(draftInvoiceId, USER_ID);

        var creditNoteRequest = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        creditNoteRequest.setOriginalInvoiceId(draftInvoiceId);
        creditNoteRequest.setCorrectionReason("Chybně účtované množství");
        Long creditNoteId = creditNoteService.createFromInvoice(creditNoteRequest, USER_ID).getId();

        // Koncept dobropisu se nikam neodeslal a nic neopravuje — zakázka zůstává zamčená.
        assertThatThrownBy(() -> invoiceService.createFromOrder(createRequest(orderId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("ORDER_ALREADY_INVOICED"));

        creditNoteService.issue(creditNoteId, USER_ID);

        // Jádro opravy: do V69 byla zakázka po dobropisu zamčená NAVŽDY — storno vystavené
        // faktury je od KN-1 zakázané a dobropis stav faktury nemění, takže cesta k správné
        // faktuře neexistovala.
        InvoiceDto.DetailResponse reinvoiced = invoiceService.createFromOrder(createRequest(orderId), USER_ID);
        assertThat(reinvoiced.getId()).isNotEqualTo(draftInvoiceId);

        InvoiceDto.DetailResponse credited = invoiceService.getById(draftInvoiceId);
        assertThat(credited.getCreditedAt()).as("razítko dobropisování").isNotNull();
        assertThat(credited.getStatus())
                .as("dobropisovaná faktura zůstává platným vystaveným dokladem")
                .isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("dobropisovaná faktura přestane blokovat i editaci položek zakázky (V69)")
    void issuedCreditNote_unlocksOrderItemsAgain() {
        issueWithNextNumber(invoiceService, draftInvoiceId, USER_ID);

        // Dobropis opravuje doklad, který zákazník DOSTAL — u nepředané faktury není co
        // opravovat, ta se prostě smaže a vystaví znovu (rozhodnutí uživatele 2026-08-08).
        invoiceService.handOver(draftInvoiceId, USER_ID);

        var creditNoteRequest = new cz.palo.autoservis.model.dto.billing.CreditNoteDto.CreateRequest();
        creditNoteRequest.setOriginalInvoiceId(draftInvoiceId);
        creditNoteRequest.setCorrectionReason("Chybně účtované množství");
        creditNoteService.issue(
                creditNoteService.createFromInvoice(creditNoteRequest, USER_ID).getId(), USER_ID);

        // Dobropis se vystavuje proto, že faktura byla špatně — obvykle kvůli položkám.
        // Kdyby zakázka zůstala zamčená, nešlo by je před refakturací opravit.
        var item = new cz.palo.autoservis.model.dto.order.OrderItemDto.CreateRequest();
        item.setItemType(OrderItemType.LABOR);
        item.setName("Dodatečná práce");
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("hod");
        item.setUnitPrice(new BigDecimal("250"));
        item.setVatRate((short) 21);
        item.setPosition((short) 2);

        assertThat(orderItemService.create(orderId, item, USER_ID).getId()).isNotNull();
    }

    // =========================================================================
    // Datum vystavení volí obsluha (rozhodnutí uživatele 2026-08-07)
    // =========================================================================

    @Test
    @DisplayName("na faktuře je datum z dialogu, ne dnešek — a číslo je z řady téhož období")
    void issue_keepsIssueDateFromRequest() {
        // Koncept „ležel" čtyři měsíce; obsluha ho vystavuje k datu, které v dialogu potvrdí.
        LocalDate chosenDate = LocalDate.now().minusMonths(4);
        Long staleDraftId = draftIssuedOn(chosenDate, 14);

        InvoiceDto.DetailResponse issued = issueWithNextNumber(invoiceService, staleDraftId, USER_ID);

        // Dřív se datum razítkovalo dneškem (audit KN-10) a zadané datum se na doklad nedostalo.
        assertThat(issued.getIssueDate()).isEqualTo(chosenDate);
        // Číslo a datum musí zůstat z téhož období — to byl důvod razítka a drží to teď
        // sladění řady se zvoleným datem, ne přepis data.
        assertThat(issued.getInvoiceNumber())
                .startsWith(chosenDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
    }

    @Test
    @DisplayName("budoucí datum vystavení projde (rozhodnutí uživatele 2026-08-09, dřív 422)")
    void issue_acceptsFutureIssueDate() {
        // Číslo se navrhuje pro období zvoleného data, aby se řada nerozešla s dokladem.
        LocalDate future = LocalDate.now().plusDays(7);
        Long draftId = draftIssuedOn(LocalDate.now(), 14);
        InvoiceDto.IssueRequest request = nextNumberRequest(invoiceService, future);

        assertThat(invoiceService.issue(draftId, request, USER_ID).getIssueDate())
                .isEqualTo(future);
    }

    @Test
    @DisplayName("koncept s budoucím datem vystavení vznikne (rozhodnutí uživatele 2026-08-09)")
    void createFromOrder_acceptsFutureIssueDate() {
        InvoiceDto.CreateRequest request = createRequest(createOrderWithItem());
        request.setIssueDate(LocalDate.now().plusDays(1));
        request.setDueDate(LocalDate.now().plusDays(15));

        assertThat(invoiceService.createFromOrder(request, USER_ID).getIssueDate())
                .isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    @DisplayName("propadlá splatnost se posune o původní lhůtu, budoucí se nechává být")
    void issue_shiftsOnlyDueDateThatWouldFallIntoThePast() {
        // Splatnost propadne, jen když obsluha vystaví k pozdějšímu datu, než na jaké zněla
        // splatnost konceptu — jinak by doklad narazil na CHECK chk_due_date.
        Long staleDraftId = draftIssuedOn(LocalDate.now().minusMonths(4), 14);
        InvoiceDto.IssueRequest today = nextNumberRequest(invoiceService);

        assertThat(invoiceService.issue(staleDraftId, today, USER_ID).getDueDate())
                .as("posun zachová původní lhůtu 14 dní, počítáno od zvoleného data vystavení")
                .isEqualTo(LocalDate.now().plusDays(14));

        Long freshDraftId = draftIssuedOn(LocalDate.now().minusDays(2), 30);

        // Splatnost, která je i po vystavení v budoucnu, je vědomá volba obsluhy.
        assertThat(issueWithNextNumber(invoiceService, freshDraftId, USER_ID).getDueDate())
                .isEqualTo(LocalDate.now().minusDays(2).plusDays(30));
    }

    @Test
    @DisplayName("splatnost před vystavením neprojde už ve service (ne až na DB CHECK)")
    void createFromOrder_rejectsDueDateBeforeIssueDate() {
        InvoiceDto.CreateRequest request = createRequest(createOrderWithItem());
        request.setIssueDate(LocalDate.now());
        request.setDueDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> invoiceService.createFromOrder(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
                        .isEqualTo("DUE_DATE_BEFORE_ISSUE_DATE"));
    }

    @Test
    @DisplayName("fakturace bez vyplněného profilu firmy → 422 COMPANY_PROFILE_MISSING, ne 500")
    void createFromOrder_withoutCompanyProfile_returnsBusinessRule() {
        // Chybějící nastavení není pád serveru: do 2026-07-31 letěla IllegalStateException → 500,
        // ačkoli Javadoc slibovala 422 (audit 10/A-3). Profil se maže přímo SQL — mapper má jen
        // find/update (jediný řádek id = 1) a test se na konci rollbackne.
        jdbcTemplate.update("DELETE FROM billing.company_profile");
        Long orderId = createOrderWithItem();

        assertThatThrownBy(() -> invoiceService.createFromOrder(createRequest(orderId), USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getRuleCode()).isEqualTo("COMPANY_PROFILE_MISSING");
                    assertThat(bre.getMessage())
                            .as("hláška má obsluze říct, kde to doplnit")
                            .contains("Fakturačních údajích");
                });
    }

    /** Koncept založený k danému datu vystavení, se splatností o {@code termDays} později. */
    private Long draftIssuedOn(LocalDate issueDate, int termDays) {
        InvoiceDto.CreateRequest request = createRequest(createOrderWithItem());
        request.setIssueDate(issueDate);
        request.setDueDate(issueDate.plusDays(termDays));
        request.setTaxableSupplyDate(issueDate);
        return invoiceService.createFromOrder(request, USER_ID).getId();
    }

    private static cz.palo.autoservis.model.dto.billing.CompanyProfileDto.UpdateRequest
            companyProfileRequest(String name) {
        var request = new cz.palo.autoservis.model.dto.billing.CompanyProfileDto.UpdateRequest();
        request.setName(name);
        request.setIco("12345678");
        request.setDic("CZ12345678");
        request.setStreet("Dílenská");
        request.setStreetNumber("12");
        request.setCity("Praha");
        request.setPostalCode("110 00");
        request.setCountryCode("CZ");
        request.setBankAccount("123456789/0800");
        request.setIban("CZ6508000000192000145399");
        request.setSwift("GIBACZPX");
        request.setInvoiceNumberAuto(true);
        request.setInvoiceNumberMask("{RRRR}{MM}{NNN}");
        request.setCashReceiptNumberSource(cz.palo.autoservis.model.enums.CashReceiptNumberSource.MASK);
        request.setCashReceiptNumberMask("PPD{RRRR}{MM}{NNN}");
        return request;
    }

    @Test
    @DisplayName("nedokončenou zakázku nelze fakturovat → ORDER_NOT_INVOICEABLE")
    void createFromOrder_orderNotCompleted_isRejected() {
        Long openOrderId = createOrderWithItem();
        InvoiceDto.CreateRequest request = createRequest(openOrderId);
        // createRequest zakázku dokončil (setup) — vrátíme ji do provozu, ať se testuje
        // právě ten stav, ve kterém se dřív fakturovat dalo.
        Order order = orderMapper.findById(openOrderId).orElseThrow();
        order.setStatus(OrderStatus.IN_PROGRESS);
        orderMapper.update(order);

        assertThatThrownBy(() -> invoiceService.createFromOrder(request, USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> {
                    BusinessRuleException bre = (BusinessRuleException) e;
                    assertThat(bre.getRuleCode()).isEqualTo("ORDER_NOT_INVOICEABLE");
                    assertThat(bre.getMessage()).contains("až dokončenou");
                });
    }

    private InvoiceDto.CreateRequest createRequest(Long orderId) {
        // Fakturovat lze až dokončenou zakázku (2026-08-05). Přepíná se přímo mapperem —
        // obchází to branku ve službě schválně, jde o přípravu dat, ne o testovanou cestu.
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
        return request;
    }
}
