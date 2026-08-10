package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.*;
import cz.palo.autoservis.model.converter.InvoiceConverter;
import cz.palo.autoservis.model.converter.InvoiceItemConverter;
import cz.palo.autoservis.model.domain.billing.*;
import cz.palo.autoservis.model.domain.customer.Address;
import cz.palo.autoservis.model.domain.customer.Customer;
import cz.palo.autoservis.model.domain.order.Order;
import cz.palo.autoservis.model.domain.order.OrderItem;
import cz.palo.autoservis.model.enums.OrderStatus;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.model.dto.billing.InvoiceItemDto;
import cz.palo.autoservis.model.dto.billing.InvoiceSearchParams;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.enums.InvoicePartyRole;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Implementace {@link InvoiceService}.
 */
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceMapper invoiceMapper;
    private final InvoiceConverter invoiceConverter;
    private final InvoiceItemMapper invoiceItemMapper;
    private final InvoiceItemConverter invoiceItemConverter;
    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final InvoicePartyMapper invoicePartyMapper;
    private final CompanyProfileMapper companyProfileMapper;
    private final CustomerMapper customerMapper;
    private final AddressMapper addressMapper;
    /** Jen pro kontrolu navázaných dokladů při mazání (V88) — viz requireNoLinkedDocuments. */
    private final CashReceiptMapper cashReceiptMapper;
    private final CreditNoteMapper creditNoteMapper;

    // =========================================================================
    // Faktura
    // =========================================================================

    /**
     * Vytvoří novou fakturu podle zadané zakázky a kontextu uživatele.
     * Metoda ověří existenci zakázky, že k ní ještě neexistuje aktivní faktura,
     * platnost fakturační adresy zákazníka, a založí novou fakturu včetně
     * stran dokladu a položek.
     *
     * @param createRequest request s údaji o zakázce a fakturační adrese
     * @param userId ID uživatele, který fakturu vytváří
     * @return detail vytvořené faktury se všemi souvisejícími údaji
     * @throws ResourceNotFoundException pokud zakázka, zákazník nebo fakturační adresa neexistuje
     * @throws BusinessRuleException s kódem {@code ORDER_NOT_INVOICEABLE} (zakázka není dokončená),
     *         {@code ORDER_ALREADY_INVOICED}, {@code ADDRESS_NOT_OWNED_BY_CUSTOMER},
     *         {@code COMPANY_PROFILE_MISSING}, nebo když zakázka nemá žádné položky k fakturaci
     */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse createFromOrder(InvoiceDto.CreateRequest createRequest, Long userId) {

        Long billingAddressId = createRequest.getBillingAddressId();

        requireDueDateNotBeforeIssueDate(createRequest.getIssueDate(), createRequest.getDueDate());

        //Zakázka vůbec existuje?
        Order order = orderMapper.findById(createRequest.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Zakázka", createRequest.getOrderId()));

        // Stornovanou zakázku nelze fakturovat (E1.5/A6). Druhý směr té samé vazby drží od KN-11
        // stavový automat zakázky: vyfakturovanou zakázku nelze zrušit (ORDER_HAS_ACTIVE_INVOICE),
        // a protože CANCELLED je terminální, zrušená zakázka se už fakturovatelnou nestane.
        // Fakturovat lze až DOKONČENOU zakázku (rozhodnutí uživatele 2026-08-05).
        // „Dokončena" nově znamená „práce hotová a vyúčtovatelná" — je to okamžik, kdy se
        // vydá materiál a od kterého má doklad co vyúčtovat. Do téhle změny šlo vystavit
        // fakturu i na zakázku ve stavu „Přijata", na které se ještě nesáhlo na auto.
        //
        // Řetěz je tedy: práce hotová → Dokončena (výdej ze skladu) → faktura → předání.
        // Vědomý dopad: zálohová faktura předem tím není možná.
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessRuleException(
                    "ORDER_NOT_INVOICEABLE", "order",
                    order.getStatus() == OrderStatus.CANCELLED
                            ? "Zrušenou zakázku nelze fakturovat."
                            : "Fakturovat lze až dokončenou zakázku — nejdřív ji přepněte "
                              + "na „Dokončena\".",
                    Map.of("orderId", order.getId(), "status", order.getStatus()));
        }

        // nemá už (aktivní) fakturu? findByOrderId po V48 vrací jen nestornovanou.
        if (invoiceMapper.findByOrderId(createRequest.getOrderId()).isPresent()) {
            throw new BusinessRuleException(
                    "ORDER_ALREADY_INVOICED",
                    "invoice",
                    "Zakázka už má fakturu.",
                    Map.of("orderId", createRequest.getOrderId()));
        }

        //zákazník existuje?
        Customer customer = customerMapper.findById(order.getCustomerId()).orElseThrow(
                () -> new ResourceNotFoundException("Zákazník", order.getCustomerId()));

        // Fakturační adresa zákazníka
        Address address = addressMapper.findById(billingAddressId).orElseThrow(
                () -> new ResourceNotFoundException("Adresa", billingAddressId)
        );

        if (!address.getCustomerId().equals(customer.getId())) {
            throw new BusinessRuleException(
                    "ADDRESS_NOT_OWNED_BY_CUSTOMER", "invoice",
                    "Fakturační adresa nepatří tomuto zákazníkovi.",
                    Map.of("addressId", billingAddressId, "customerId", customer.getId()));
        }

        // Profil firmy se kontroluje před INSERTem: chybějící profil není pád serveru
        // (dřív IllegalStateException → 500), ale nevyplněné nastavení, se kterým obsluha
        // může něco udělat → 422 s návodem (audit 10/A-3).
        CompanyProfile company = companyProfileMapper.find().orElseThrow(
                () -> new BusinessRuleException(
                        "COMPANY_PROFILE_MISSING", "companyProfile",
                        "Není vyplněný profil firmy — doplňte ho ve Fakturačních údajích, "
                                + "jinak nemá faktura dodavatele.",
                        Map.of()));

        // hlavička faktury (bez čísla a VS — ty vzniknou až při vystavení)
        Invoice invoice = invoiceConverter.toDomain(createRequest);
        invoice.setCustomerId(order.getCustomerId());
        invoice.setCreatedBy(userId);
        invoice.setCustomerNameSnapshot(customer.getDisplayName());
        invoice.setOrderNumberSnapshot(order.getOrderNumber());
        // Vozidlo se na faktuře zmrazuje celé (K-5) — VIN/značka/model se přes PUT vozidla mění,
        // právní doklad je ale nesmí zpětně sledovat.
        invoice.setVehicleLicensePlateSnapshot(order.getVehicleLicensePlate());
        invoice.setVehicleVinSnapshot(order.getVehicleVin());
        invoice.setVehicleBrandSnapshot(order.getVehicleBrand());
        invoice.setVehicleModelSnapshot(order.getVehicleModel());
        try {
            invoiceMapper.insert(invoice);
        } catch (DuplicateKeyException e) {
            // Koncept se zakládá bez čísla, takže uq_invoice_number tu padnout nemůže (NULLy
            // se v unikátu nepočítají) — zbývá uq_invoices_order_active, tedy dvojí fakturace
            // zakázky v souběhu. Pre-check výše dává hezčí 422 pro nezávodní případ.
            if (!String.valueOf(e.getMessage()).contains("uq_invoices_order")) {
                throw e;
            }
            throw new BusinessRuleException(
                    "ORDER_ALREADY_INVOICED", "invoice",
                    "Zakázka už má fakturu.",
                    Map.of("orderId", createRequest.getOrderId()));
        }

        InvoiceParty invoiceParty = new InvoiceParty();
        invoiceParty.setInvoiceId(invoice.getId());
        invoiceParty.setRole(InvoicePartyRole.CUSTOMER);
        invoiceParty.setName(customer.getDisplayName());
        invoiceParty.setIco(customer.getIco());
        invoiceParty.setDic(customer.getDic());
        invoiceParty.setStreet(address.getStreet());
        invoiceParty.setStreetNumber(address.getStreetNumber());
        invoiceParty.setCity(address.getCity());
        invoiceParty.setPostalCode(address.getPostalCode());
        invoiceParty.setCountryCode(address.getCountryCode());

        invoicePartyMapper.insert(invoiceParty);

        // dodavatel (naše firma) — zmražený snapshot z company_profile (načtený výše).
        InvoiceParty supplierParty = new InvoiceParty();
        supplierParty.setInvoiceId(invoice.getId());
        supplierParty.setRole(InvoicePartyRole.SUPPLIER);
        supplierParty.setName(company.getName());
        supplierParty.setIco(company.getIco());
        supplierParty.setDic(company.getDic());
        supplierParty.setStreet(company.getStreet());
        supplierParty.setStreetNumber(company.getStreetNumber());
        supplierParty.setCity(company.getCity());
        supplierParty.setPostalCode(company.getPostalCode());
        supplierParty.setCountryCode(company.getCountryCode());
        supplierParty.setBankAccount(company.getBankAccount());
        supplierParty.setIban(company.getIban());
        supplierParty.setSwift(company.getSwift());

        invoicePartyMapper.insert(supplierParty);

        //načtení položek zakázky a převedení na položky faktury
        List<OrderItem> orderItems = orderItemMapper.findByOrderId(createRequest.getOrderId());
        List<InvoiceItem> invoiceItems = orderItems.stream().map(invoiceItemConverter::fromOrderItem).toList();

        if (invoiceItems.isEmpty()) {
            throw new BusinessRuleException(
                    "ORDER_HAS_NO_ITEMS", "invoice",
                    "Zakázku bez položek nelze fakturovat.",
                    Map.of("orderId", order.getId()));
        }

        //uložení položek faktury
        invoiceItemMapper.insertBatch(invoiceItems, invoice.getId());

        // Vrať hotovou fakturu s položkami
        return getById(invoice.getId());

    }


    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud faktura s daným ID neexistuje
     */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse update(Long id, InvoiceDto.UpdateRequest updateRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice existingInvoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        requireEditable(existingInvoice);

        // `UpdateRequest` nese jen splatnost — datum vystavení se přes PUT nemění, bere se z dokladu.
        requireDueDateNotBeforeIssueDate(existingInvoice.getIssueDate(), updateRequest.getDueDate());

        // Stav se přes update NIKDY nemění — jen přes issue/markPaid/cancel (a jejich guardovaný
        // UPDATE, K5). Zapamatovat ho MUSÍME před applyUpdate: konvertor mutuje objekt na místě
        // a vrací tutéž referenci, takže po applyUpdate už `existingInvoice` nese stav z requestu
        // a `updated.setStatus(existingInvoice.getStatus())` by byl no-op (TD-49).
        InvoiceStatus originalStatus = existingInvoice.getStatus();
        Invoice updated = invoiceConverter.applyUpdate(existingInvoice, updateRequest);
        updated.setStatus(originalStatus);
        // Guarded write (TD-58/S-4): mapper.update má WHERE status='DRAFT'. requireEditable výše
        // dá pro běžný (nezávodní) případ hezčí 422; 0 řádků tady znamená, že fakturu mezi kontrolou
        // a zápisem přepnul souběžný issue() → 409, ne tichá mutace vystaveného dokladu.
        if (invoiceMapper.update(updated) == 0) {
            throw invoiceStateChanged(id);
        }
        return fetchOrFail(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException pokud je {@code id} null
     */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse issue(Long id, InvoiceDto.IssueRequest issueRequest, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (issueRequest == null) {
            throw new IllegalArgumentException("issueRequest nesmí být null");
        }
        return transitionTo(id, InvoiceStatus.ISSUED, issueRequest);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException pokud je {@code id} null
     */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse markPaid(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        // Z1: vyrušený doklad se neplatí. Dobropis fakturu ekonomicky vynuloval, takže
        // evidovat na ni úhradu je účetní nesmysl. Opačné pořadí (zaplaceno, pak dobropis)
        // legitimní je — tam se peníze vracejí a `Zaplacena` u dobropisované to má připomínat.
        Invoice beforePayment = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));
        if (beforePayment.getCreditedAt() != null) {
            throw new BusinessRuleException(
                    "INVOICE_CREDITED", "invoice",
                    "K faktuře je vystavený opravný daňový doklad, takže je vyrušená — "
                            + "úhradu na ni evidovat nelze.",
                    Map.of("invoiceId", id));
        }

        InvoiceDto.DetailResponse paid = transitionTo(id, InvoiceStatus.PAID);
        // Evidence úhrady (E2.1/K-9): datum (DB NOW()), částka a skutečný způsob
        // (MVP = předepsaný payment_method). Částečné úhrady = R-3 var. b.
        //
        // Zapisuje se `totalToPay`, ne `totalGross` (V67/KN-7): u hotovostní faktury je to
        // zaokrouhlená částka, kterou zákazník opravdu zaplatil a kterou nese i pokladní
        // doklad. Dřív tu byl nezaokrouhlený součet, takže pohledávka byla „vyrovnaná"
        // na jinou hodnotu, než kolik přišlo do pokladny.
        invoiceMapper.recordPayment(id, paid.getTotalToPay(), paid.getPaymentMethod());

        // Zaplacení zároveň orazítkuje předání, není-li ještě potvrzené (2026-08-08).
        // „Kdo platí, doklad má" je pravidlo, podle kterého se aplikace stejně řídí v obou
        // strážcích — zaplacená faktura nejde smazat a dobropis se u ní povoluje. Bez tohohle
        // by u faktury zaplacené na místě zůstalo `handed_over_at = NULL`, tedy záznam
        // tvrdící „zákazník doklad nedostal" o dokladu, se kterým se zachází jako s předaným.
        // Guard `markHandedOver` (status ISSUED/PAID a dosud nepředáno) hlídá idempotenci,
        // takže dřívější ruční potvrzení tohle datum nepřepíše.
        invoiceMapper.markHandedOver(id, userId);
        return getById(id);
    }

    /**
     * Odmítne smazání faktury, na které visí pokladní doklad nebo dobropis.
     *
     * <p><strong>Živá je přes pokladní doklad</strong>, ne přes dobropis: PPD jde vystavit
     * i k nepředané faktuře, kdežto dobropis jen k předané — a předaná se nesmaže tak jako tak.
     * Ta větev je proto obranná. <em>Nemazat jako mrtvý kód:</em> nedosažitelnost je vlastnost
     * současných pravidel mazání, ne trvalý fakt. Přesně na tomhle se to spletlo už dvakrát.
     *
     * <p>Tuhle kontrolu chtěl nález KN-12 a byla tehdy vyhodnocena jako **nedosažitelná**:
     * PPD i dobropis jdou vystavit jen k ISSUED/PAID faktuře a ta se nedala smazat vůbec,
     * takže by šlo o mrtvý kód (R-12). Otevřením mazání nepředané faktury (V88) se to
     * změnilo — a invariant hlídaný testem `delete_isRejectedWhileDocumentsAreLinkedToTheInvoice`
     * okamžitě spadl: mazání sice neprošlo, ale zastavil ho až cizí klíč, takže obsluze
     * probublala `DataIntegrityViolationException` místo české hlášky.
     */
    private void requireNoLinkedDocuments(Invoice invoice) {
        int receipts = cashReceiptMapper.findByInvoiceId(invoice.getId()).size();
        int creditNotes = creditNoteMapper.findByOriginalInvoiceId(invoice.getId()).size();
        if (receipts == 0 && creditNotes == 0) {
            return;
        }
        throw new BusinessRuleException(
                "INVOICE_HAS_LINKED_DOCUMENTS", "invoice",
                "Na fakturu jsou navázané doklady "
                        + (receipts > 0 ? "(pokladní doklad" : "(")
                        + (receipts > 0 && creditNotes > 0 ? ", " : "")
                        + (creditNotes > 0 ? "opravný daňový doklad" : "")
                        + "). Nejdřív je vyřešte"
                        + (receipts > 0 ? " — pokladní doklad (i stornovaný) jde smazat" : "")
                        + ".",
                Map.of("invoiceId", invoice.getId(),
                       "cashReceipts", receipts,
                       "creditNotes", creditNotes));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Očekávaná čísla se skládají <strong>toutéž metodou, která je přiděluje</strong>
     * ({@code mask.format}), a porovnají se se skutečností. Kontrola se tím nemůže
     * s generátorem rozejít — druhý parser čísel v projektu neexistuje.
     */
    @Override
    public InvoiceDto.NumberGapsResponse findNumberGaps() {
        InvoiceDto.NumberGapsResponse response = new InvoiceDto.NumberGapsResponse();

        CompanyProfile company = companyProfileMapper.find().orElse(null);
        if (company == null || !Boolean.TRUE.equals(company.getInvoiceGapCheckEnabled())) {
            return response;   // enabled = false; prázdný seznam NEznamená „vše v pořádku"
        }
        response.setEnabled(true);

        LocalDate today = LocalDate.now();
        response.setPeriodDate(today);
        DocumentNumberMask mask = parseMaskOrFail(company.getInvoiceNumberMask());

        Long maxSequence = invoiceMapper.findMaxSequence(mask.regex(today));
        if (maxSequence == null) {
            return response;   // v období zatím nic — není mezi čím být mezera
        }

        // Startovní číslo platí, jen když patří do TOHOTO období; v dalším měsíci by
        // jinak umlčelo celou řadu. Nezadané (nebo cizí) = hlídá se od pořadí 1.
        long from = 1L;
        String configuredFrom = company.getInvoiceGapCheckFrom();
        if (configuredFrom != null && !configuredFrom.isBlank()) {
            from = mask.sequenceOf(configuredFrom.trim(), today).orElse(1L);
        }

        Set<String> existing = new HashSet<>(invoiceMapper.findNumbersByRegex(mask.regex(today)));
        List<String> missing = new ArrayList<>();
        for (long seq = from; seq <= maxSequence; seq++) {
            String expected = mask.format(today, seq);
            if (!existing.contains(expected)) {
                missing.add(expected);
            }
        }
        response.setMissingNumbers(missing);
        return response;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse revokeIssue(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        if (invoice.getStatus() != InvoiceStatus.ISSUED) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_ISSUED", "invoice",
                    invoice.getStatus() == InvoiceStatus.PAID
                            ? "Faktura je zaplacená — nejdřív vezměte platbu zpět."
                            : "Do konceptu lze vrátit jen vystavenou fakturu.",
                    Map.of("invoiceId", id, "status", invoice.getStatus()));
        }
        if (invoice.getHandedOverAt() != null) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_DELETABLE", "invoice",
                    "Fakturu už dostal zákazník — do konceptu ji vrátit nelze. Vystavený "
                            + "doklad se opravuje opravným daňovým dokladem (dobropisem). "
                            + "Pokud jste předání označili omylem, vezměte ho zpět.",
                    Map.of("invoiceId", id));
        }
        requireNoLinkedDocuments(invoice);

        // Dva kroky, viz komentář u dotazů: trigger neměnnosti čísla (V71) nedovolí zrušit
        // vystavení a smazat číslo jedním UPDATEem.
        if (invoiceMapper.revokeIssueStep1Status(id) == 0) {
            throw invoiceStateChanged(id);
        }
        invoiceMapper.revokeIssueStep2ClearNumber(id);
        return getById(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse revokePayment(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        if (invoice.getStatus() != InvoiceStatus.PAID) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_PAID", "invoice",
                    "Faktura není označená jako zaplacená, takže není co vracet.",
                    Map.of("invoiceId", id, "status", invoice.getStatus()));
        }

        // Pokladní doklad je číslovaný doklad vlastní řady — nemůže viset na faktuře, která
        // se tváří nezaplaceně. Stornuje se zvlášť a ten postup už existuje (V68).
        cashReceiptMapper.findActiveByInvoiceId(id).ifPresent(receipt -> {
            throw new BusinessRuleException(
                    "INVOICE_HAS_CASH_RECEIPT", "invoice",
                    "K faktuře je vystavený pokladní doklad — nejdřív ho stornujte, "
                            + "teprve pak lze vzít platbu zpět.",
                    Map.of("invoiceId", id, "cashReceiptId", receipt.getId()));
        });

        // Předání se NEVRACÍ: jsou to dvě nezávislé věci a razítko mohlo vzniknout dřív
        // ručně. Kdo doklad opravdu nemá, vezme předání zpět zvlášť.
        if (invoiceMapper.clearPayment(id) == 0) {
            throw invoiceStateChanged(id);
        }
        return getById(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse handOver(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_ISSUED", "invoice",
                    "Koncept se nepředává — nejdřív fakturu vystavte.",
                    Map.of("invoiceId", id, "status", invoice.getStatus()));
        }
        if (invoice.getHandedOverAt() != null) {
            return getById(id);   // idempotentní: druhé kliknutí nic nemění
        }
        if (invoiceMapper.markHandedOver(id, userId) == 0) {
            throw invoiceStateChanged(id);
        }
        return getById(id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public InvoiceDto.DetailResponse revokeHandOver(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        if (invoice.getPaidAt() != null) {
            throw new BusinessRuleException(
                    "INVOICE_ALREADY_PAID", "invoice",
                    "U zaplacené faktury nelze předání vzít zpět — zákazník ji má.",
                    Map.of("invoiceId", id));
        }
        // Z2: u vyrušeného dokladu taky ne. Bez toho by vznikla kombinace „nepředaná
        // + dobropisovaná", kterou dobropis sám vylučuje (vystavit ho lze jen k předané).
        // Dosud to zastavila až kontrola navázaných dokladů při mazání — tedy pojistka,
        // ne pravidlo.
        if (invoice.getCreditedAt() != null) {
            throw new BusinessRuleException(
                    "INVOICE_CREDITED", "invoice",
                    "K faktuře je vystavený opravný daňový doklad — předání už vzít zpět nelze.",
                    Map.of("invoiceId", id));
        }
        if (invoice.getHandedOverAt() == null) {
            return getById(id);   // idempotentní
        }
        if (invoiceMapper.clearHandedOver(id) == 0) {
            throw invoiceStateChanged(id);
        }
        return getById(id);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException pokud je {@code id} null
     */
    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        // Doklad, který zákazník DOSTAL, je právní dokument a opravuje se dobropisem
        // (§42/§45 ZDPH, audit KN-1). Ten důvod ale platí až od okamžiku předání — do V88
        // ho aplikace přisuzovala už samotnému vystavení, přestože o odeslání ani předání
        // nevěděla nic a fakturu sama neposílá. Za každý překlep při vystavení pak
        // v evidenci ležela dvojice dokladů dokazující, že se někdo upsal.
        //
        // Hláška rozlišuje důvod, ať obsluha ví, co dělat: předaný doklad → dobropis,
        // zaplacený → taky (kdo platí, doklad má).
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            if (invoice.getPaidAt() != null) {
                throw new BusinessRuleException(
                        "INVOICE_NOT_DELETABLE", "invoice",
                        "Zaplacenou fakturu smazat nelze — zákazník ji má. Opravuje se "
                                + "opravným daňovým dokladem (dobropisem).",
                        Map.of("invoiceId", id, "status", invoice.getStatus()));
            }
            if (invoice.getHandedOverAt() != null) {
                throw new BusinessRuleException(
                        "INVOICE_NOT_DELETABLE", "invoice",
                        "Fakturu už dostal zákazník, takže ji smazat nelze — vystavený "
                                + "doklad se opravuje opravným daňovým dokladem (dobropisem). "
                                + "Pokud jste předání označili omylem, vezměte ho zpět.",
                        Map.of("invoiceId", id, "status", invoice.getStatus()));
            }
        }

        requireNoLinkedDocuments(invoice);

        // Guarded write (vzor K5): 0 řádků = mezitím ji někdo předal, zaplatil nebo vystavil
        // → 409, ne tiché nic. Podmínky jsou i v SQL, ne jen tady.
        if (invoiceMapper.deleteDeletable(id) == 0) {
            throw invoiceStateChanged(id);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PagedResponse<InvoiceDto.ListResponse> getPage(InvoiceSearchParams params) {
        List<InvoiceListRow> rows = invoiceMapper.search(params);
        List<InvoiceDto.ListResponse> content = invoiceConverter.toListResponses(rows);
        long total = invoiceMapper.countSearch(params);
        return PagedResponse.of(content, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud faktura s daným ID neexistuje
     */
    @Override
    public InvoiceDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        return buildDetail(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nic nerezervuje — je to jen návrh pro předvyplnění dialogu. Souběh dvou
     * uživatelů se stejným návrhem vyřeší až unikátní constraint při INSERTu
     * (druhý dostane {@code DUPLICATE_INVOICE_NUMBER} a FE si řekne o nový návrh).
     */
    @Override
    public InvoiceDto.NextNumberResponse suggestNextNumber(LocalDate issueDate) {
        InvoiceDto.NextNumberResponse response = new InvoiceDto.NextNumberResponse();
        CompanyProfile company = companyProfileMapper.find().orElse(null);
        if (company == null || !Boolean.TRUE.equals(company.getInvoiceNumberAuto())) {
            response.setAuto(false);
            return response;
        }

        LocalDate date = issueDate != null ? issueDate : LocalDate.now();
        DocumentNumberMask mask = parseMaskOrFail(company.getInvoiceNumberMask());
        Long maxSequence = invoiceMapper.findMaxSequence(mask.regex(date));
        String suggestion = mask.format(date, (maxSequence == null ? 0L : maxSequence) + 1);

        if (suggestion.length() > DocumentNumberMask.MAX_NUMBER_LENGTH) {
            throw new BusinessRuleException(
                    "INVOICE_NUMBER_SERIES_OVERFLOW", "invoiceNumber",
                    "Řada přetekla — další číslo „" + suggestion + "“ přesahuje "
                            + DocumentNumberMask.MAX_NUMBER_LENGTH
                            + " znaků. Upravte masku ve Fakturačních údajích.",
                    Map.of("suggestion", suggestion, "mask", mask.source()));
        }

        response.setAuto(true);
        response.setInvoiceNumber(suggestion);
        return response;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException pokud faktura s daným číslem neexistuje
     */
    @Override
    public InvoiceDto.DetailResponse getByInvoiceNumber(String invoiceNumber) {
        Invoice invoice = invoiceMapper.findByInvoiceNumber(invoiceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", invoiceNumber));

        return buildDetail(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code orderId} null
     * @throws ResourceNotFoundException pokud k dané zakázce žádná faktura neexistuje
     */
    @Override
    public InvoiceDto.DetailResponse getByOrderId(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId nesmí být null");
        }
        Invoice invoice = invoiceMapper.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", orderId));

        return buildDetail(invoice);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException pokud je {@code customerId} null
     */
    @Override
    public List<InvoiceDto.ListResponse> getByCustomerId(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId nesmí být null");
        }
        return invoiceConverter.toListResponses(invoiceMapper.findByCustomerId(customerId));
    }

    // =========================================================================
    // Položky faktury
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code invoiceId} null
     * @throws ResourceNotFoundException pokud faktura s daným ID neexistuje
     */
    @Override
    @Transactional
    public InvoiceItemDto.Response addItem(Long invoiceId, InvoiceItemDto.CreateRequest createRequest) {
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId nesmí být null");
        }
        Invoice invoice = invoiceMapper.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", invoiceId));
        requireEditable(invoice);

        // Položka faktury musí patřit ke stejné zakázce jako faktura (E1.5/S-3) — jinak by
        // faktura odkazovala na položku cizí zakázky (rozbitá auditní stopa, dvojí navěšení).
        Long orderItemId = createRequest.getOrderItemId();
        OrderItem orderItem = orderItemMapper.findById(orderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Položka zakázky", orderItemId));
        if (!orderItem.getOrderId().equals(invoice.getOrderId())) {
            throw new BusinessRuleException(
                    "ITEM_NOT_OF_INVOICED_ORDER", "orderItemId",
                    "Položka nepatří k fakturované zakázce.",
                    Map.of("orderItemId", orderItemId, "orderId", invoice.getOrderId()));
        }

        InvoiceItem item = invoiceItemConverter.toDomain(createRequest);
        item.setInvoiceId(invoiceId);
        // Guarded write (TD-58/S-4): insert projde jen když je faktura stále DRAFT.
        if (invoiceItemMapper.insert(item) == 0) {
            throw invoiceStateChanged(invoiceId);
        }

        return fetchItemOrFail(item.getId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code itemId} null
     * @throws ResourceNotFoundException pokud položka faktury s daným ID neexistuje
     */
    @Override
    @Transactional
    public InvoiceItemDto.Response updateItem(Long itemId, InvoiceItemDto.UpdateRequest updateRequest) {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId nesmí být null");
        }
        InvoiceItem existingItem = invoiceItemMapper.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Položka faktury", itemId));
        requireEditableForItem(existingItem.getInvoiceId());

        InvoiceItem updated = invoiceItemConverter.applyUpdate(existingItem, updateRequest);
        // Guarded write (TD-58/S-4): 0 řádků = faktura mezitím opustila DRAFT (nebo položka
        // zmizela) → 409, ne 500. requireEditableForItem výše dává 422 pro nezávodní případ.
        if (invoiceItemMapper.update(updated) == 0) {
            throw invoiceStateChanged(existingItem.getInvoiceId());
        }

        return fetchItemOrFail(itemId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code itemId} null
     * @throws ResourceNotFoundException pokud položka faktury s daným ID neexistuje
     */
    @Override
    @Transactional
    public void deleteItem(Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId nesmí být null");
        }
        InvoiceItem item = invoiceItemMapper.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Položka faktury", itemId));
        requireEditableForItem(item.getInvoiceId());

        // Guarded write (TD-58/S-4): smaže jen když je faktura stále DRAFT.
        if (invoiceItemMapper.deleteById(itemId) == 0) {
            throw invoiceStateChanged(item.getInvoiceId());
        }
    }

    // =========================================================================
    // Privátní pomocné metody
    // =========================================================================

    /** Přechod bez vlastních dat dokladu (markPaid). */
    private InvoiceDto.DetailResponse transitionTo(Long id, InvoiceStatus target) {
        return transitionTo(id, target, null);
    }

    private InvoiceDto.DetailResponse transitionTo(Long id, InvoiceStatus target,
                                                   InvoiceDto.IssueRequest issueRequest) {
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", id));

        InvoiceStatus current = invoice.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException(
                    "INVALID_STATUS_TRANSITION", "invoice",
                    "Neplatná změna stavu faktury.",
                    Map.of("invoiceId", id, "from", current, "to", target));
        }

        // Vystavit lze jen fakturu s aspoň jednou položkou (E1.5/S-2) — v DRAFTu jdou všechny
        // položky smazat, a vystavená (nezměnitelná) faktura na 0 Kč je nesmyslný daňový doklad.
        if (target == InvoiceStatus.ISSUED
                && invoiceItemMapper.findByInvoiceId(id).isEmpty()) {
            throw new BusinessRuleException(
                    "INVOICE_HAS_NO_ITEMS", "invoice",
                    "Fakturu bez položek nelze vystavit.",
                    Map.of("invoiceId", id));
        }

        // Guarded UPDATE (WHERE status = current) chrání proti souběžnému přechodu,
        // který by se vklínil mezi kontrolu výše a tento zápis (TOCTOU) — pre-check
        // výše dál dává hezčí 422 pro běžný (nezávodní) případ.
        int affectedRows = target == InvoiceStatus.ISSUED
                ? stampNumberAndIssue(invoice, issueRequest)
                : invoiceMapper.updateStatus(id, target, current);
        if (affectedRows == 0) {
            throw invoiceStateChanged(id);
        }
        return fetchOrFail(id);
    }

    /**
     * Vystavení: doklad dostane <strong>číslo</strong>, variabilní symbol a datum vystavení
     * z dialogu a přejde do ISSUED — vše jedním guardovaným UPDATEem.
     *
     * <h3>Proč číslo až tady</h3>
     * <p>Koncept číslo nemá, takže ho ani nemůže spálit: zrušený koncept nedělá do řady
     * mezeru a čísla jdou vzestupně podle data vystavení, ne podle pořadí zakládání
     * (rozhodnutí uživatele 2026-08-02, varianta C).
     *
     * <h3>Datum posílá dialog, server ho nepřepisuje</h3>
     * <p>Doklad odchází s datem, které obsluha vidí a potvrdí v dialogu vystavení
     * (předvyplněné datem z konceptu). Dřív se datum razítkovalo dneškem (audit KN-10),
     * takže zadané datum se na fakturu nedostalo; od rozhodnutí uživatele 2026-08-07 platí,
     * co obsluha zadá. Hodnota není ničím omezená — zpětné datování i <strong>budoucí
     * datum</strong> jsou povolené (budoucí od rozhodnutí uživatele 2026-08-09, dřív 422
     * {@code ISSUE_DATE_IN_FUTURE}); zodpovědnost za soulad s řadou a přiznáním nese obsluha.
     *
     * <p>Číslo se skládá z <strong>téhož</strong> data ({@link #suggestNextNumber} dostává
     * z dialogu {@code issueDate}), takže se s dokladem nemůže rozejít o období — to byl
     * původní důvod razítka a řeší se teď tímhle sladěním, ne přepisem data.
     *
     * <h3>Číslo posílá dialog</h3>
     * <p>Číslo vždy přichází v requestu — dialog vystavení ho při zapnutém automatu předvyplní
     * návrhem podle masky ({@link #suggestNextNumber}), při vypnutém nechá pole prázdné, a v obou
     * režimech ho obsluha může přepsat. Přepínač {@code invoice_number_auto} tedy řídí
     * <strong>předvyplnění</strong>, ne to, jestli se aplikace ptá (rozhodnutí uživatele 2026-08-02).
     *
     * <p>Nad řadou se přesto drží {@code pg_advisory_xact_lock} (regex masky pro zvolené datum
     * nese tvar řady i období, takže zámek odpovídá „jedna řada, jedno období"): kontrola
     * unikátnosti a zápis čísla jsou díky němu vůči souběžnému vystavení atomické.
     *
     * <p>Splatnost se posouvá <strong>jen když by ji obsluhou zvolené datum vystavení předběhlo</strong>
     * — jinak by doklad narazil na CHECK {@code chk_due_date}. Posun zachová původní lhůtu
     * splatnosti (např. „14 dní"), protože to je to, co obsluha při zakládání mínila; splatnost,
     * která je i po posunu data v budoucnu, se nechává být.
     *
     * <p>Datum zdanitelného plnění se nemění — to je fakt okamžiku plnění, ne vystavení
     * (§21 ZDPH), a být dřív než vystavení má u něj legitimní důvod.
     */
    private int stampNumberAndIssue(Invoice invoice, InvoiceDto.IssueRequest issueRequest) {
        LocalDate issueDate = issueRequest.getIssueDate();
        LocalDate dueDate = invoice.getDueDate();

        if (dueDate != null && dueDate.isBefore(issueDate)) {
            long paymentTermDays = invoice.getIssueDate() == null
                    ? 0
                    : ChronoUnit.DAYS.between(invoice.getIssueDate(), dueDate);
            dueDate = issueDate.plusDays(Math.max(paymentTermDays, 0));
        }

        lockNumberSeriesFor(issueDate);
        String number = requireUsableInvoiceNumber(trimToNull(issueRequest.getInvoiceNumber()));
        String variableSymbol = trimToNull(issueRequest.getVariableSymbol());

        try {
            return invoiceMapper.issueWithNumber(invoice.getId(), issueDate, dueDate, number, variableSymbol);
        } catch (DuplicateKeyException e) {
            // Zámek drží jen souběh nad *stejnou* řadou; ruční číslo z cizí řady se pod ním
            // proklouznout dá. Constraint uq_invoice_number je konečná pojistka — přeložit na
            // stejné 422 jako pre-check, ať obsluha dostane radu, ne 500.
            throw duplicateInvoiceNumber(number);
        }
    }

    /**
     * Poradní zámek nad číselnou řadou daného období — drží se do konce transakce, takže
     * kontrola unikátnosti a zápis čísla jsou vůči souběžnému vystavení atomické.
     *
     * <p>Při vypnutém automatu (nebo bez profilu firmy) není co zamykat: čísla si v tom
     * režimu řídí obsluha a unikátnost jistí {@code uq_invoice_number}.
     */
    private void lockNumberSeriesFor(LocalDate issueDate) {
        CompanyProfile company = companyProfileMapper.find().orElse(null);
        if (company == null || !Boolean.TRUE.equals(company.getInvoiceNumberAuto())) {
            return;
        }
        invoiceMapper.lockNumberSeries(parseMaskOrFail(company.getInvoiceNumberMask()).regex(issueDate));
    }

    /**
     * Validace čísla faktury při vystavení: neprázdnost a unikátnost. Unikátnost finálně
     * jistí {@code uq_invoice_number} — tenhle pre-check dává hezčí 422.
     *
     * @return číslo připravené k zápisu
     */
    private String requireUsableInvoiceNumber(String number) {
        if (number == null || number.isEmpty()) {
            // @NotBlank v DTO tohle normálně chytí dřív; tady jen pro jistotu přímých volání.
            throw new BusinessRuleException(
                    "INVOICE_NUMBER_MISSING", "invoiceNumber",
                    "Číslo faktury je povinné.",
                    Map.of());
        }

        if (invoiceMapper.findByInvoiceNumber(number).isPresent()) {
            throw duplicateInvoiceNumber(number);
        }
        return number;
    }

    /** Ořízne bílé znaky; prázdné pole z formuláře se ukládá jako {@code null}. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Maska z profilu firmy je validovaná při ukládání nastavení; nevalidní stav tu
     * znamená poškozená data (ruční zásah do DB) — 422 s hláškou parseru je pořád
     * lepší diagnóza než 500.
     */
    private DocumentNumberMask parseMaskOrFail(String mask) {
        try {
            return DocumentNumberMask.parse(mask);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "INVALID_INVOICE_NUMBER_MASK", "invoiceNumberMask",
                    "Maska číselné řady v nastavení není platná: " + e.getMessage(),
                    Map.of("mask", String.valueOf(mask)));
        }
    }

    private BusinessRuleException duplicateInvoiceNumber(String number) {
        return new BusinessRuleException(
                "DUPLICATE_INVOICE_NUMBER", "invoiceNumber",
                "Číslo faktury „" + number + "“ už existuje — zvolte jiné.",
                Map.of("invoiceNumber", String.valueOf(number)));
    }

    /** 409 pro guarded-write, který nezasáhl žádný řádek — faktura mezitím opustila DRAFT (TOCTOU, TD-58/K5). */
    private ConflictException invoiceStateChanged(Long invoiceId) {
        return new ConflictException("INVOICE_STATE_CHANGED",
                "Fakturu " + invoiceId + " mezitím změnil někdo jiný. Načtěte ji znovu.");
    }

    /**
     * Splatnost nesmí předcházet vystavení (audit 02/F-9). DB to hlídá CHECK {@code chk_due_date},
     * jenže ten propadne jako {@code DataIntegrityViolationException} s hláškou o porušeném
     * omezení — obsluha se z ní nedozví, které pole opravit. Business validace patří do service (R-13).
     */
    private void requireDueDateNotBeforeIssueDate(LocalDate issueDate, LocalDate dueDate) {
        if (issueDate != null && dueDate != null && dueDate.isBefore(issueDate)) {
            throw new BusinessRuleException(
                    "DUE_DATE_BEFORE_ISSUE_DATE", "dueDate",
                    "Datum splatnosti nesmí být dřív než datum vystavení.",
                    Map.of("issueDate", issueDate, "dueDate", dueDate));
        }
    }

    private void requireEditable(Invoice invoice) {
        if (!invoice.getStatus().isEditable()) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_EDITABLE", "invoice",
                    "Fakturu v tomto stavu už nelze upravovat.",
                    Map.of("invoiceId", invoice.getId(), "status", invoice.getStatus()));
        }
    }

    private void requireEditableForItem(Long invoiceId) {
        Invoice invoice = invoiceMapper.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", invoiceId));
        requireEditable(invoice);
    }

    private InvoiceDto.DetailResponse fetchOrFail(Long id) {
        Invoice invoice = invoiceMapper.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Faktura " + id + " zmizela mezi UPDATE a SELECT"));

        return buildDetail(invoice);
    }

    /**
     * Poskládá detail faktury: načte položky, spočtené součty, strany dokladu,
     * zakázku a rozpis DPH. Faktura bez položek nemá řádek v součtovém view,
     * použije se proto nulový souhrn.
     */
    private InvoiceDto.DetailResponse buildDetail(Invoice invoice) {
        List<InvoiceItemDto.Response> items = invoiceItemConverter.toListResponses(
                invoiceItemMapper.findByInvoiceId(invoice.getId()));

        InvoiceSummary summary = invoiceMapper.findSummaryByInvoiceId(invoice.getId())
                .orElseGet(() -> InvoiceSummary.zero(invoice.getId()));

        List<InvoiceParty> parties = invoicePartyMapper.findByInvoiceId(invoice.getId());

        Order order = invoice.getOrderId() == null ? null
                : orderMapper.findById(invoice.getOrderId()).orElse(null);

        List<InvoiceVatSummary> vatSummary = invoiceMapper.findVatSummaryByInvoiceId(invoice.getId());

        return invoiceConverter.toDetailResponse(invoice, items, summary, parties, order, vatSummary);
    }

    private InvoiceItemDto.Response fetchItemOrFail(Long itemId) {
        return invoiceItemMapper.findById(itemId)
                .map(invoiceItemConverter::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Položka faktury " + itemId + " zmizela mezi INSERT/UPDATE a SELECT"));
    }
}
