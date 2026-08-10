package cz.palo.autoservis.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.ReceiptReviewMapper;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.converter.ReceiptConverter;
import cz.palo.autoservis.model.domain.warehouse.*;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.DraftSupplier;
import cz.palo.autoservis.model.draft.FieldState;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.draft.TrackedField;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import cz.palo.autoservis.model.dto.warehouse.ReceiptSearchParams;
import cz.palo.autoservis.service.DraftVerificationService;
import cz.palo.autoservis.service.ProductMatchingService;
import cz.palo.autoservis.service.ReceiptReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReceiptReviewServiceImpl implements ReceiptReviewService {

    private static final String RESOURCE = "Příjemka";

    private final ReceiptReviewMapper mapper;
    private final WarehouseImportMapper importMapper;
    private final cz.palo.autoservis.mapper.ProductMatchingMapper matchingMapper;
    private final cz.palo.autoservis.mapper.SupplierMapper supplierMapper;
    /** Jen kvůli reaktivaci deaktivované karty dílu při potvrzení příjemky (KN-16). */
    private final cz.palo.autoservis.mapper.WarehouseMapper warehouseMapper;
    private final DraftVerificationService verificationService;
    private final ProductMatchingService productMatchingService;
    private final cz.palo.autoservis.config.WarehouseImportProperties importProperties;
    private final cz.palo.autoservis.service.DraftAssembler draftAssembler;
    private final ReceiptConverter converter;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ čtení

    @Override
    public PagedResponse<ReceiptDto.ListResponse> list(ReceiptSearchParams params) {
        List<ReceiptDto.ListResponse> content = mapper.search(params).stream()
                .map(converter::toListResponse)
                .toList();
        long total = mapper.countSearch(params);
        return PagedResponse.of(content, params.getPage(), params.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID neexistuje
     */
    @Override
    public ReceiptDto.DetailResponse getDetail(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        GoodsReceipt receipt = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        return converter.toDetailResponse(receipt, deserializeDraft(receipt), mapper.hasPdf(id));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID nemá PDF
     */
    @Override
    public GoodsReceipt getPdf(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        return mapper.findPdfById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE + " (PDF)", id));
    }

    // ------------------------------------------------------------------ create (manual)

    @Override
    @Transactional
    public ReceiptDto.DetailResponse createManualDraft(ReceiptDto.CreateDraftRequest request,
                                                       Long userId) {
        DraftSupplier supplier;
        if (request.getSupplierId() != null) {
            Supplier existing = supplierMapper.findById(request.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Dodavatel", request.getSupplierId()));
            supplier = DraftSupplier.builder()
                    .extracted(DraftSupplier.Extracted.builder()
                            .name(existing.getName())
                            .registrationNumber(existing.getRegistrationNumber())
                            .vatId(existing.getVatId())
                            .build())
                    .matchedSupplierId(existing.getId())
                    .matchState(DraftSupplier.MatchState.AUTO)
                    .build();
        } else {
            supplier = DraftSupplier.builder()
                    .extracted(DraftSupplier.Extracted.builder()
                            .name(request.getSupplierName())
                            .registrationNumber(request.getSupplierRegistrationNumber())
                            .build())
                    .matchState(DraftSupplier.MatchState.NONE)
                    .build();
        }

        ReceiptDraft draft = ReceiptDraft.builder()
                .schemaVersion(ReceiptDraft.CURRENT_SCHEMA_VERSION)
                .documentType(request.getDocumentType())
                .sourceChannel(ReceiptSource.MANUAL)
                .header(emptyHeader())
                .supplier(supplier)
                .vatRecap(new ArrayList<>())
                .deliveryNoteRefs(new ArrayList<>())
                .lines(new ArrayList<>())
                .checks(new ArrayList<>())
                .build();
        draftAssembler.fillDerivedValues(draft);   // currency DEFAULTED apod.
        boolean reconciliationOk = verificationService.verify(draft);

        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierId(supplier.getMatchedSupplierId())
                .supplierNameSnapshot(supplier.getExtracted() == null
                        ? null : supplier.getExtracted().getName())
                .currency(draft.getHeader().getCurrency().getValue())
                .documentType(request.getDocumentType())
                .sourceChannel(ReceiptSource.MANUAL)
                .status(ReceiptStatus.PENDING_REVIEW)
                .reconciliationOk(reconciliationOk)
                .draftPayload(serializeDraft(draft))
                .createdBy(userId)
                .build();
        importMapper.insertReceipt(receipt);

        return getDetail(receipt.getId());
    }

    private ReceiptDraft.Header emptyHeader() {
        return ReceiptDraft.Header.builder()
                .documentNumber(cz.palo.autoservis.model.draft.TrackedField.absent())
                .orderNumber(cz.palo.autoservis.model.draft.TrackedField.absent())
                .originalOrderNumber(cz.palo.autoservis.model.draft.TrackedField.absent())
                .issueDate(cz.palo.autoservis.model.draft.TrackedField.absent())
                .dueDate(cz.palo.autoservis.model.draft.TrackedField.absent())
                .taxableSupplyDate(cz.palo.autoservis.model.draft.TrackedField.absent())
                .currency(cz.palo.autoservis.model.draft.TrackedField.absent())
                .subtotal(cz.palo.autoservis.model.draft.TrackedField.absent())
                .vatAmount(cz.palo.autoservis.model.draft.TrackedField.absent())
                .totalAmount(cz.palo.autoservis.model.draft.TrackedField.absent())
                .build();
    }

    // ------------------------------------------------------------------ draft

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID neexistuje
     */
    @Override
    @Transactional
    public ReceiptDto.DetailResponse updateDraft(Long id, ReceiptDraft draft, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        GoodsReceipt receipt = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requirePendingReview(receipt);

        // TD-59: vstup je surový model bez @Valid. Neúplný payload (např. {}) → 400, ne NPE→500.
        requireWellFormedDraft(draft);
        // TD-59: klient smí editovat HODNOTY, ne stavy polí. Kódem-vlastněné stavy (VERIFIED nastavuje
        // jen deterministická kontrola, DEFAULTED assembler) se z příchozího draftu srazí na VERBATIM —
        // pipeline níže je legitimně přepočte; padělaný „VERIFIED" se tak nedostane do JSONB.
        sanitizeClientDraft(draft);
        // TD-59: typ dokladu a kanál jsou autoritativní ze sloupců příjemky (nastaveny při založení),
        // ne z těla — jinak by se JSONB payload rozešel se sloupci document_type/source_channel.
        draft.setDocumentType(receipt.getDocumentType());
        draft.setSourceChannel(receipt.getSourceChannel());

        // přečíslovat pozice (řádky mohly přibýt/ubýt) a dopočíst dopočitatelné
        renumberLines(draft);
        draftAssembler.fillDerivedValues(draft);

        // znovu přepočítat kontroly a stavy — editace mohla součty spravit i rozbít
        boolean reconciliationOk = verificationService.verify(draft);
        // a znovu napárovat řádky (uživatel mohl opravit katalogové číslo);
        // CONFIRMED volby uživatele kaskáda nikdy nepřepisuje
        productMatchingService.matchLines(draft);
        verificationService.matchDeliveryNoteRefs(draft, id);

        var h = draft.getHeader();
        DraftSupplier supplier = draft.getSupplier();
        int updated = mapper.updateDraft(id,
                serializeDraft(draft),
                h.getDocumentNumber().getValue(),
                h.getOrderNumber().getValue(),
                h.getOriginalOrderNumber().getValue(),
                h.getIssueDate().getValue(),
                h.getDueDate().getValue(),
                h.getTaxableSupplyDate().getValue(),
                h.getSubtotal().getValue(),
                h.getVatAmount().getValue(),
                h.getTotalAmount().getValue(),
                h.getCurrency().getValue(),
                supplier == null ? null : supplier.getMatchedSupplierId(),
                supplier == null || supplier.getExtracted() == null
                        ? null : supplier.getExtracted().getName(),
                reconciliationOk);
        requireUpdated(updated, id);

        return getDetail(id);   // verify-and-fetch (R-03)
    }

    // ------------------------------------------------------------------ confirm

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID neexistuje
     */
    @Override
    @Transactional
    public ReceiptDto.DetailResponse confirm(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        GoodsReceipt receipt = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requirePendingReview(receipt);

        ReceiptDraft draft = deserializeDraft(receipt);
        if (draft == null) {
            throw new BusinessRuleException("RECEIPT_DRAFT_MISSING",
                    "Příjemka nemá draft — nelze potvrdit.");
        }

        boolean reconciliationOk = verificationService.verify(draft);
        verificationService.matchDeliveryNoteRefs(draft, id);

        String documentNumber = draft.getHeader().getDocumentNumber().getValue();
        String supplierName = draft.getSupplier().getExtracted().getName();

        // Duplicita se hlásí JAKO PRVNÍ, ještě před completeness gate (audit KN-4b). Doklad,
        // který v systému už je, nemá smysl nechat obsluhu dopracovávat — jinak vyřeší párování
        // položek a teprve pak se dozví, že celý import zahodí. Dotaz nic nezapisuje a stojí
        // jen na datech z draftu, takže ho lze udělat takhle brzy.
        //
        // Varianta podle JMÉNA je tu kvůli dodavateli bez čitelného IČO: `resolveSupplier` by
        // mu pokaždé založil novou kartu a dedup podle supplier_id (níž) by pak neměl s čím
        // porovnávat — týž ručně psaný doklad šel naskladnit opakovaně.
        if (documentNumber != null && willCreateNewSupplier(draft.getSupplier())
                && importMapper.existsActiveDocumentBySupplierName(supplierName, documentNumber, id)) {
            throw new ConflictException("DUPLICATE_IMPORT",
                    "Doklad " + documentNumber + " od dodavatele " + supplierName
                            + " už v systému je. Pokud jde o jiný doklad, opravte jeho číslo.");
        }

        validateCompleteness(draft);

        // čísla DL, jejichž řádky se NEnaskladní (uživatel zvolil „jen provázat")
        java.util.Set<String> linkedNumbers = linkedDeliveryNoteNumbers(draft);
        requireLinkedNotesAreAttributable(draft, linkedNumbers);

        // dodavatel: napárovaný, nebo se teď založí z extrahovaných dat
        Long supplierId = resolveSupplier(draft.getSupplier());

        // dedup podruhé — draft mohl vzniknout bez napárovaného dodavatele
        if (importMapper.existsActiveDocument(supplierId, documentNumber, id)) {
            throw new ConflictException("DUPLICATE_IMPORT",
                    "Doklad " + documentNumber + " od tohoto dodavatele už existuje.");
        }

        // materializace: produkty + šarže + pohyby RECEIPT (trigger navýší sklad)
        for (DraftLine line : draft.getLines()) {
            if (line.getLineKind() != DraftLine.LineKind.ITEM) {
                continue;   // skupinové/NOTE řádky se nikdy nematerializují
            }
            if (line.getDeliveryNoteNumber() != null
                    && linkedNumbers.contains(line.getDeliveryNoteNumber())) {
                continue;   // zboží už přišlo dodacím listem — jen provázáno, nenaskladňovat
            }
            Long productId = resolveProduct(line);

            // samoučení převodníku: příště se stejný kód dodavatele napáruje sám.
            // Bez katalogového čísla není co učit (supplier_products.supplier_sku je NOT NULL) —
            // řádek napárovaný na existující kartu bez kódu se do převodníku nezapisuje.
            if (line.getCatalogNumber().getValue() != null) {
                matchingMapper.upsertSupplierProduct(supplierId,
                        line.getCatalogNumber().getValue(), productId,
                        line.getName().getValue(), line.getUnitPriceExclVat().getValue());
            }

            GoodsReceiptItem batch = GoodsReceiptItem.builder()
                    .goodsReceiptId(id)
                    .productId(productId)
                    .position(line.getPosition())
                    .nameSnapshot(line.getName().getValue())
                    .quantityReceived(line.getQuantity().getValue())
                    .quantityRemaining(line.getQuantity().getValue())
                    .unitPriceExclVat(line.getUnitPriceExclVat().getValue())
                    .vatRate(line.getVatRate().getValue())
                    .totalInclVat(line.getTotalInclVat().getValue())
                    .build();
            importMapper.insertReceiptItem(batch);

            importMapper.insertMovement(StockMovement.builder()
                    .productId(productId)
                    .batchId(batch.getId())
                    .movementType(MovementType.RECEIPT)
                    .quantity(line.getQuantity().getValue())
                    .note("Příjem z dokladu " + documentNumber)
                    .createdBy(userId)
                    .build());
        }

        int updated = mapper.confirm(id, supplierId, supplierName,
                serializeDraft(draft), reconciliationOk, userId);
        requireUpdated(updated, id);

        // zafixovat DL reference včetně rozhodnutí kontrolora
        if (draft.getDeliveryNoteRefs() != null) {
            for (var ref : draft.getDeliveryNoteRefs()) {
                importMapper.upsertDeliveryNoteRef(id, ref.getNumber(),
                        ref.getMatchedReceiptId(), ref.getResolution());
            }
        }

        return getDetail(id);
    }

    /** Dodavatel se při potvrzení teprve založí (není napárovaný na existující kartu). */
    private boolean willCreateNewSupplier(DraftSupplier supplier) {
        return supplier != null && supplier.getMatchedSupplierId() == null;
    }

    /**
     * Ověří, že volbu „jen provázat" jde vůbec uplatnit — tedy že jde poznat, které řádky
     * faktury dodací list kryje (audit KN-4a).
     *
     * <p><strong>Proč to raději odmítneme, než odhadneme.</strong> Přeskočení řádku v
     * materializaci se ptá na {@code DraftLine.deliveryNoteNumber}, jenže extrakce ho podle
     * kontraktu plní <em>jen u skupinového řádku</em> {@code DELIVERY_NOTE_GROUP}, ne u
     * položek — a materializují se právě položky. Podmínka proto nikdy neplatila a zboží se
     * naskladnilo podruhé, tiše.
     *
     * <p>Doplnit chybějící vazbu odhadem (např. „skupinový řádek platí pro položky pod ním")
     * by ale znamenalo hádat rozvržení dokladu; při špatném odhadu by se zboží, které fyzicky
     * přišlo, <em>nenaskladnilo vůbec</em> — a tichá chyba tímhle směrem je horší než dnešní
     * duplicita. Dokud přiřazení řádků k dodacímu listu neexistuje (prompt + kontrolní
     * obrazovka), potvrzení v takové situaci raději zastavíme a řekneme proč. Naskladnit
     * doklad normálně jde dál — stačí provázání nepoužít.
     */
    private void requireLinkedNotesAreAttributable(ReceiptDraft draft, java.util.Set<String> linkedNumbers) {
        if (linkedNumbers.isEmpty()) {
            return;
        }
        java.util.List<String> unattributable = linkedNumbers.stream()
                .filter(number -> draft.getLines().stream()
                        .noneMatch(line -> line.getLineKind() == DraftLine.LineKind.ITEM
                                && number.equals(line.getDeliveryNoteNumber())))
                .sorted()
                .toList();
        if (unattributable.isEmpty()) {
            return;
        }
        throw new BusinessRuleException(
                "DELIVERY_NOTE_LINK_NOT_APPLICABLE", "deliveryNoteRefs",
                "U dodacího listu " + String.join(", ", unattributable)
                        + " nejde určit, které řádky faktury kryje, takže volbu „pouze provázat\" "
                        + "zatím nelze použít. Doklad naskladněte bez provázání, nebo řádky "
                        + "patřící tomuto dodacímu listu z faktury odeberte.",
                Map.of("deliveryNoteNumbers", unattributable));
    }

    /** Čísla DL s rozhodnutím LINKED — jejich ITEM řádky se nematerializují. */
    private java.util.Set<String> linkedDeliveryNoteNumbers(ReceiptDraft draft) {
        java.util.Set<String> numbers = new java.util.HashSet<>();
        if (draft.getDeliveryNoteRefs() == null) return numbers;
        for (var ref : draft.getDeliveryNoteRefs()) {
            if (ref.getMatchedReceiptId() != null && "LINKED".equals(ref.getResolution())) {
                numbers.add(ref.getNumber());
            }
        }
        return numbers;
    }

    // ------------------------------------------------------------------ reject

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID neexistuje
     */
    @Override
    @Transactional
    public ReceiptDto.DetailResponse reject(Long id, String note, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        GoodsReceipt receipt = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requirePendingReview(receipt);

        requireUpdated(mapper.reject(id, note, userId), id);
        return getDetail(id);
    }

    // ------------------------------------------------------------------ storno

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException  pokud je {@code id} null
     * @throws ResourceNotFoundException pokud příjemka s daným ID neexistuje
     * @throws BusinessRuleException     pokud příjemka není CONFIRMED
     *                                   ({@code RECEIPT_NOT_CANCELLABLE}) nebo se z jejích
     *                                   šarží už čerpalo ({@code RECEIPT_ALREADY_USED})
     */
    @Override
    @Transactional
    public ReceiptDto.DetailResponse cancel(Long id, String note, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        GoodsReceipt receipt = mapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));

        // stornovat lze jen potvrzenou příjemku; draft se zamítá (reject),
        // stornovanou už není co stornovat
        if (receipt.getStatus() != ReceiptStatus.CONFIRMED) {
            throw new BusinessRuleException("RECEIPT_NOT_CANCELLABLE",
                    "Stornovat lze jen potvrzenou příjemku — tahle je "
                            + switch (receipt.getStatus()) {
                                case PENDING_REVIEW -> "ke kontrole (použijte zamítnutí)";
                                case REJECTED -> "zamítnutá";
                                case CANCELLED -> "už stornovaná";
                                default -> "v jiném stavu";
                            } + ".");
        }

        // zámek šarží do konce transakce — mezi kontrolou a kompenzací nesmí nikdo vydat
        List<GoodsReceiptItem> batches = mapper.findBatchesForUpdate(id);
        requireNotUsed(id, batches);

        // kompenzace: záporný ADJUSTMENT na plné přijaté množství každé šarže.
        // Trigger sníží quantity_on_hand i quantity_remaining na nulu; nic se nemaže.
        String documentNumber = receipt.getInvoiceNumber();
        for (GoodsReceiptItem batch : batches) {
            importMapper.insertMovement(StockMovement.builder()
                    .productId(batch.getProductId())
                    .batchId(batch.getId())
                    .movementType(MovementType.ADJUSTMENT)
                    .quantity(batch.getQuantityReceived().negate())
                    .note("Storno příjemky " + (documentNumber == null ? id : documentNumber))
                    .createdBy(userId)
                    .build());
        }

        requireUpdated(mapper.cancel(id, note, userId), id);
        return getDetail(id);
    }

    /**
     * Storno smí projít jen u <b>nedotčené</b> příjemky. Kontroluje se obojí:
     * <ul>
     *   <li>zůstatek šarže — změnil ho výdej <i>nebo</i> ruční korekce/odpis;
     *       kompenzace plného přijatého množství by pak stlačila zůstatek pod nulu
     *       (CHECK {@code chk_items_remaining}),</li>
     *   <li>vazba z položek zakázky — vydané a zase vrácené zboží má zůstatek zpět,
     *       ale FK z {@code order_items} trvá ({@code ON DELETE RESTRICT}).</li>
     * </ul>
     */
    private void requireNotUsed(Long receiptId, List<GoodsReceiptItem> batches) {
        List<Long> touchedBatches = batches.stream()
                .filter(b -> b.getQuantityRemaining().compareTo(b.getQuantityReceived()) != 0)
                .map(GoodsReceiptItem::getId)
                .toList();
        List<Long> linkedToOrders = mapper.findBatchIdsUsedByOrderItems(receiptId);

        if (!touchedBatches.isEmpty() || !linkedToOrders.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (!touchedBatches.isEmpty()) params.put("touchedBatches", touchedBatches);
            if (!linkedToOrders.isEmpty()) params.put("batchesOnOrders", linkedToOrders);
            throw new BusinessRuleException("RECEIPT_ALREADY_USED", null,
                    "Ze šarží této příjemky se už čerpalo (výdej na zakázku, korekce "
                            + "nebo odpis) — stornovat ji nelze. Nesrovnalost řešte "
                            + "ruční korekcí na kartě dílu.", params);
        }
    }

    // ------------------------------------------------------------------ pomocné

    /** Přečísluje pozice řádků 1..n (řádky mohly v review přibýt nebo ubýt). */
    private void renumberLines(ReceiptDraft draft) {
        if (draft.getLines() == null) return;
        int position = 1;
        for (DraftLine line : draft.getLines()) {
            line.setPosition(position++);
        }
    }

    /**
     * TD-59: minimální strukturální kontrola surového draftu z těla PUT. Chybí-li hlavička nebo
     * seznam řádků (typicky tělo {@code {}}), jde o neúplný payload → 400, ne NPE→500 dál v pipeline.
     */
    private void requireWellFormedDraft(ReceiptDraft draft) {
        if (draft == null || draft.getHeader() == null || draft.getLines() == null) {
            throw new IllegalArgumentException(
                    "Payload draftu je neúplný — očekává se kompletní draft z kontrolní obrazovky.");
        }
    }

    /**
     * TD-59: normalizace příchozího draftu na hranici „AI čte, kód počítá". Klient smí editovat
     * hodnoty polí, ale ne jejich stav — kódem-vlastněné stavy (VERIFIED = ověřeno deterministickou
     * kontrolou, DEFAULTED = dosazeno assemblerem) se srazí na VERBATIM. Chybějící {@link TrackedField}
     * se doplní jako {@code absent()} (null-safety pro navazující pipeline). VERIFIED/DEFAULTED pak
     * legitimně přiřadí až {@code verify()} / {@code fillDerivedValues}; padělaný stav se nezaslouží.
     */
    private void sanitizeClientDraft(ReceiptDraft draft) {
        ReceiptDraft.Header h = draft.getHeader();
        h.setDocumentNumber(sane(h.getDocumentNumber()));
        h.setOrderNumber(sane(h.getOrderNumber()));
        h.setOriginalOrderNumber(sane(h.getOriginalOrderNumber()));
        h.setIssueDate(sane(h.getIssueDate()));
        h.setDueDate(sane(h.getDueDate()));
        h.setTaxableSupplyDate(sane(h.getTaxableSupplyDate()));
        h.setCurrency(sane(h.getCurrency()));
        h.setSubtotal(sane(h.getSubtotal()));
        h.setVatAmount(sane(h.getVatAmount()));
        h.setTotalAmount(sane(h.getTotalAmount()));

        for (DraftLine line : draft.getLines()) {
            line.setCatalogNumber(sane(line.getCatalogNumber()));
            line.setName(sane(line.getName()));
            line.setUnit(sane(line.getUnit()));
            line.setQuantity(sane(line.getQuantity()));
            line.setUnitPriceExclVat(sane(line.getUnitPriceExclVat()));
            line.setVatRate(sane(line.getVatRate()));
            line.setTotalExclVat(sane(line.getTotalExclVat()));
            line.setTotalInclVat(sane(line.getTotalInclVat()));
        }
    }

    /** null → {@code absent()}; kódem-vlastněný stav (VERIFIED/DEFAULTED) z klienta → VERBATIM. */
    private <T> TrackedField<T> sane(TrackedField<T> field) {
        if (field == null) {
            return TrackedField.absent();
        }
        if (field.getState() == FieldState.VERIFIED || field.getState() == FieldState.DEFAULTED) {
            field.setState(FieldState.VERBATIM);
        }
        return field;
    }

    private void requirePendingReview(GoodsReceipt receipt) {
        if (receipt.getStatus() != ReceiptStatus.PENDING_REVIEW) {
            throw new BusinessRuleException("RECEIPT_NOT_EDITABLE",
                    "Příjemka už byla " + (receipt.getStatus() == ReceiptStatus.CONFIRMED
                            ? "potvrzena" : "zamítnuta") + " — nelze ji měnit.");
        }
    }

    private void requireUpdated(int updatedRows, Long id) {
        if (updatedRows == 0) {
            throw new ConflictException("RECEIPT_ALREADY_PROCESSED",
                    "Příjemku " + id + " mezitím zpracoval někdo jiný.");
        }
    }

    /**
     * Completeness gate: nic ABSENT na povinných polích, aspoň jeden ITEM řádek,
     * dodavatel vyřešitelný. Chyby se sbírají a vracejí najednou.
     */
    private void validateCompleteness(ReceiptDraft draft) {
        Map<String, Object> missing = new LinkedHashMap<>();
        var h = draft.getHeader();

        if (h.getDocumentNumber().getValue() == null) missing.put("documentNumber", "chybí");
        if (h.getIssueDate().getValue() == null) missing.put("issueDate", "chybí");
        if (h.getCurrency().getValue() == null) {
            missing.put("currency", "chybí");
        } else if (!"CZK".equalsIgnoreCase(h.getCurrency().getValue())) {
            // R-F: ceny šarží by se v EUR dál tvářily jako CZK — blokujeme do zavedení kurzů
            missing.put("currency", "nepodporovaná měna: " + h.getCurrency().getValue()
                    + " — podporována je jen CZK");
        }
        if (h.getSubtotal().getValue() == null) missing.put("subtotal", "chybí");
        if (h.getVatAmount().getValue() == null) missing.put("vatAmount", "chybí");
        if (h.getTotalAmount().getValue() == null) missing.put("totalAmount", "chybí");

        DraftSupplier supplier = draft.getSupplier();
        boolean supplierResolvable = supplier != null
                && (supplier.getMatchedSupplierId() != null
                    || (supplier.getExtracted() != null && supplier.getExtracted().getName() != null));
        if (!supplierResolvable) missing.put("supplier", "chybí");

        List<DraftLine> items = draft.getLines() == null ? List.of()
                : draft.getLines().stream()
                        .filter(l -> l.getLineKind() == DraftLine.LineKind.ITEM)
                        .toList();
        if (items.isEmpty()) missing.put("lines", "doklad nemá žádnou položku");

        // nevyřešené DL reference: napárovaný dodací list bez rozhodnutí provázat/naskladnit
        List<String> unresolvedDeliveryNotes = new ArrayList<>();
        if (draft.getDeliveryNoteRefs() != null) {
            for (var ref : draft.getDeliveryNoteRefs()) {
                if (ref.getMatchedReceiptId() != null && ref.getResolution() == null) {
                    unresolvedDeliveryNotes.add(ref.getNumber());
                }
            }
        }
        if (!unresolvedDeliveryNotes.isEmpty()) {
            missing.put("unresolvedDeliveryNotes", unresolvedDeliveryNotes);
        }
        java.util.Set<String> linkedNumbers = linkedDeliveryNoteNumbers(draft);

        List<Integer> badLines = new ArrayList<>();
        List<Integer> invalidUnits = new ArrayList<>();
        List<Integer> unresolvedMatches = new ArrayList<>();
        for (DraftLine line : items) {
            // řádky kryté LINKED dodacím listem se nematerializují — nevaliduji je
            if (line.getDeliveryNoteNumber() != null
                    && linkedNumbers.contains(line.getDeliveryNoteNumber())) {
                continue;
            }
            // Z-4: jednotka mimo uzavřený číselník (case-insensitive) — bez DB CHECKu,
            // chyba se vrací tady, kde ji jde v review srozumitelně opravit
            if (!importProperties.isAllowedUnit(
                    line.getUnit() == null ? null : line.getUnit().getValue())) {
                invalidUnits.add(line.getPosition());
            }
            // Katalogové číslo (= budoucí products.sku, NOT NULL) je povinné jen u řádku,
            // který zakládá NOVÝ produkt. Řádek napárovaný na existující kartu (AUTO/CONFIRMED
            // s productId) SKU nepotřebuje — resolveProduct použije productId. Nový produkt
            // bez čísla zůstává blokovaný: skladovou kartu bez identity založit nelze.
            boolean createsNewProduct = line.getProductMatch() == null
                    || line.getProductMatch().getProductId() == null;
            boolean catalogOk = !createsNewProduct || line.getCatalogNumber().getValue() != null;

            boolean ok = line.getName().getValue() != null
                    && catalogOk
                    && isPositive(line.getQuantity().getValue())
                    // ceny musí být NEzáporné (E6.5/S-3) — záporná cena (např. slevový řádek přečtený
                    // AI) by jinak založila šarži se zápornou nákupní cenou a rozbila ocenění skladu
                    && isNonNegative(line.getUnitPriceExclVat().getValue())
                    && line.getVatRate().getValue() != null
                    && isNonNegative(line.getTotalInclVat().getValue());
            if (!ok) badLines.add(line.getPosition());

            // SUGGESTED = kaskáda našla kandidáty a čeká na volbu člověka
            if (line.getProductMatch() != null
                    && line.getProductMatch().getState() == DraftLine.ProductMatch.State.SUGGESTED) {
                unresolvedMatches.add(line.getPosition());
            }
        }
        if (!badLines.isEmpty()) missing.put("invalidLines", badLines);
        if (!invalidUnits.isEmpty()) missing.put("invalidUnits", invalidUnits);
        if (!unresolvedMatches.isEmpty()) missing.put("unresolvedMatches", unresolvedMatches);

        if (!missing.isEmpty()) {
            throw new BusinessRuleException("RECEIPT_INCOMPLETE", null,
                    "Příjemku nelze potvrdit — doplňte chybějící údaje.", missing);
        }
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isNonNegative(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0;
    }

    /**
     * Vrátí ID dodavatele pro potvrzovanou příjemku — napárovaného, nebo nově založeného.
     *
     * <p><strong>Deaktivovaný dodavatel (audit KN-16):</strong> párování v kontrole draftu
     * hledá jen aktivní firmy, takže deaktivovaný dodavatel se nenapáruje a spadl by sem na
     * založení nového. Unikát {@code uq_suppliers_registration_number} ale platí bez ohledu na
     * {@code is_active}, takže insert skončil porušením constraintu a uživatel dostal
     * neinformativní 422 „Data se nepodařilo uložit" bez šance zjistit proč. Odmítáme proto
     * vědomě a s návodem — <em>reaktivaci neděláme automaticky</em>: deaktivace dodavatele je
     * rozhodnutí obsluhy (ukončená spolupráce, duplicitní záznam) a tiché oživení importem by ho
     * obcházelo. U karty dílu je to naopak, viz {@link #resolveProduct}.
     */
    private Long resolveSupplier(DraftSupplier supplier) {
        if (supplier.getMatchedSupplierId() != null) {
            return supplier.getMatchedSupplierId();
        }
        var e = supplier.getExtracted();

        String registrationNumber = e.getRegistrationNumber();
        if (registrationNumber != null && !registrationNumber.isBlank()) {
            var inactive = importMapper.findInactiveSupplierIdByIco(registrationNumber);
            if (inactive.isPresent()) {
                throw new BusinessRuleException(
                        "SUPPLIER_INACTIVE", "supplier",
                        "Dodavatel s IČO " + registrationNumber + " je v systému deaktivovaný. "
                                + "Aktivujte ho v sekci Dodavatelé a příjemku potvrďte znovu.",
                        Map.of("registrationNumber", registrationNumber, "supplierId", inactive.get()));
            }
        }

        Supplier created = Supplier.builder()
                .name(e.getName())
                .registrationNumber(e.getRegistrationNumber())
                .vatId(e.getVatId())
                .street(e.getStreet())
                .city(e.getCity())
                .postalCode(e.getPostalCode())
                .bankAccount(e.getBankAccount())
                .iban(e.getIban())
                .swift(e.getSwift())
                .build();
        importMapper.insertSupplier(created);
        return created.getId();
    }

    /**
     * Vyřešení produktu z výsledku párovací kaskády: AUTO/CONFIRMED s productId
     * → existující karta; jinak (NONE, nebo CONFIRMED volba „nový produkt")
     * se karta založí. SUGGESTED sem nedojde — blokuje ho completeness gate.
     *
     * <p><strong>Deaktivovaná karta (audit KN-16):</strong> {@code uq_products_sku} platí bez
     * ohledu na {@code is_active}, takže založení nové karty pro už existující (jen vyřazené)
     * SKU spadlo na porušení constraintu → 422 „Data se nepodařilo uložit" a příjemku nešlo
     * dokončit vůbec. Kartu proto <em>reaktivujeme</em>: zboží fyzicky dorazilo, takže vyřazená
     * karta se zásobou by navíc zmizela z ocenění skladu i z inventury. Opačné rozhodnutí než
     * u dodavatele ({@link #resolveSupplier}) — tam jde o vztah k firmě, tady o fyzický kus
     * na regálu.
     */
    private Long resolveProduct(DraftLine line) {
        DraftLine.ProductMatch match = line.getProductMatch();
        if (match != null && match.getProductId() != null) {
            return match.getProductId();
        }

        String catalogNumber = line.getCatalogNumber().getValue();
        // pojistka proti duplicitě sku (UNIQUE) — stejný kód už kartu má
        var existing = importMapper.findProductIdBySku(catalogNumber);
        if (existing.isPresent()) {
            return existing.get();
        }

        var inactive = importMapper.findInactiveProductIdBySku(catalogNumber);
        if (inactive.isPresent()) {
            warehouseMapper.activate(inactive.get());
            return inactive.get();
        }

        // Z-4: jednotka řádku prošla completeness gate (isAllowedUnit) — kartu
        // ale zakládáme v kanonické podobě, ať sklad neuloží „KS" místo „ks"
        String canonicalUnit = importProperties.canonicalUnit(line.getUnit().getValue());
        Product product = Product.builder()
                .sku(catalogNumber)
                .name(line.getName().getValue())
                .manufacturerPartNumber(stripBrandPrefix(catalogNumber))
                .unit(canonicalUnit != null ? canonicalUnit : "ks")
                .defaultVatRate(line.getVatRate().getValue())
                .build();
        importMapper.insertProduct(product);
        return product.getId();
    }

    /**
     * Heuristika: brand prefix dodavatele (2–4 písmena + mezera, např.
     * "EL 871.180") se do čísla výrobce nepočítá. Jen pro předvyplnění —
     * uživatel může na kartě opravit.
     */
    private String stripBrandPrefix(String catalogNumber) {
        if (catalogNumber == null) return null;
        String[] tokens = catalogNumber.trim().split("\\s+", 2);
        if (tokens.length == 2 && tokens[0].matches("[A-Za-z]{2,4}")) {
            return tokens[1];
        }
        return catalogNumber;
    }

    // ------------------------------------------------------------------ JSON

    private ReceiptDraft deserializeDraft(GoodsReceipt receipt) {
        if (receipt.getDraftPayload() == null) return null;
        try {
            return objectMapper.readValue(receipt.getDraftPayload(), ReceiptDraft.class);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Draft příjemky " + receipt.getId() + " nejde přečíst.", e);
        }
    }

    private String serializeDraft(ReceiptDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JacksonException e) {
            throw new IllegalStateException("Serializace draftu příjemky selhala.", e);
        }
    }
}
