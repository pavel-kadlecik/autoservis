package cz.palo.autoservis.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.mapper.WarehouseImportMapper;
import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.DraftSupplier;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDraftDto;
import cz.palo.autoservis.service.DraftAssembler;
import cz.palo.autoservis.service.DraftVerificationService;
import cz.palo.autoservis.service.PdfDocumentExtractionService;
import cz.palo.autoservis.service.ProductMatchingService;
import cz.palo.autoservis.service.WarehouseImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Import dokladu: extrakce → draft → kontroly → INSERT jediného řádku
 * goods_receipts (PENDING_REVIEW + JSONB draft). Nic se nenaskladňuje;
 * dodavatel se nezakládá — obojí dělá až potvrzení příjemky.
 */
@Service
@RequiredArgsConstructor
public class WarehouseImportServiceImpl implements WarehouseImportService {

    private final PdfDocumentExtractionService extractionService;
    private final cz.palo.autoservis.service.IsdocParser isdocParser;
    private final DraftAssembler draftAssembler;
    private final DraftVerificationService verificationService;
    private final ProductMatchingService productMatchingService;
    private final WarehouseImportMapper mapper;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-6}")
    private String extractionModel;

    @Override
    @Transactional
    public ReceiptDraftDto.ImportResponse importFromPdf(byte[] pdfBytes, String filename,
                                                        DocumentType documentType, String mimeType,
                                                        Long userId) {

        // 1) AI čte — PDF i fotka/sken (R-D), rozliší je MIME typ
        DocumentExtractionResult extracted = extractionService.extract(pdfBytes, documentType, mimeType);

        // 2) kód skládá kanonický draft (mapování sazeb, dopočty, defaulty)
        ReceiptDraft draft = draftAssembler.assemble(extracted, documentType, extractionModel);

        // 3) kód počítá — deterministické kontroly + párování dodavatele
        boolean reconciliationOk = verificationService.verify(draft);

        // 3b) návrhy párování řádků na skladové karty (kaskáda, fáze 5)
        productMatchingService.matchLines(draft);

        // 3c) dedup DL ↔ faktura: dohledat už přijaté dodací listy (fáze 7)
        verificationService.matchDeliveryNoteRefs(draft, null);

        // 4) idempotence — jen když známe dodavatele i číslo dokladu;
        //    zamítnuté doklady číslo uvolňují (partial unique index V39)
        Long supplierId = draft.getSupplier().getMatchedSupplierId();
        String documentNumber = draft.getHeader().getDocumentNumber().getValue();
        if (supplierId != null && documentNumber != null
                && mapper.existsActiveDocument(supplierId, documentNumber, null)) {
            throw new ConflictException("DUPLICATE_IMPORT",
                    "Doklad " + documentNumber + " od tohoto dodavatele už je naimportovaný.");
        }

        // 5) hlavička = projekce draftu; draft samotný do JSONB
        GoodsReceipt receipt = buildReceipt(draft, pdfBytes, filename, reconciliationOk, userId);
        mapper.insertReceipt(receipt);   // naplní receipt.id

        // 6) reference na dodací listy do vazební tabulky (resolution doplní review)
        for (var ref : draft.getDeliveryNoteRefs()) {
            mapper.upsertDeliveryNoteRef(receipt.getId(), ref.getNumber(),
                    ref.getMatchedReceiptId(), ref.getResolution());
        }

        return buildResponse(receipt, draft);
    }

    @Override
    @Transactional
    public ReceiptDraftDto.ImportResponse importFromIsdoc(byte[] xmlBytes, String filename, Long userId) {

        // 1) parser čte — strojová data, žádná AI; vše VERBATIM, chybějící ABSENT
        ReceiptDraft draft = isdocParser.parse(xmlBytes);

        // 2) dopočty jen tam, kde doklad hodnotu neuvádí (nikdy nepřepisuje vyplněné)
        draftAssembler.fillDerivedValues(draft);

        // 3) dál úplně stejná pipeline jako u AI importu — payoff kanonického draftu
        boolean reconciliationOk = verificationService.verify(draft);
        productMatchingService.matchLines(draft);
        verificationService.matchDeliveryNoteRefs(draft, null);

        Long supplierId = draft.getSupplier().getMatchedSupplierId();
        String documentNumber = draft.getHeader().getDocumentNumber().getValue();
        if (supplierId != null && documentNumber != null
                && mapper.existsActiveDocument(supplierId, documentNumber, null)) {
            throw new ConflictException("DUPLICATE_IMPORT",
                    "Doklad " + documentNumber + " od tohoto dodavatele už je naimportovaný.");
        }

        // ISDOC nemá PDF ani extrakční model — sloupce zůstávají prázdné
        GoodsReceipt receipt = buildReceipt(draft, null, filename, reconciliationOk, userId);
        receipt.setSourceChannel(ReceiptSource.ISDOC);
        receipt.setExtractionModel(null);
        mapper.insertReceipt(receipt);

        for (var ref : draft.getDeliveryNoteRefs()) {
            mapper.upsertDeliveryNoteRef(receipt.getId(), ref.getNumber(),
                    ref.getMatchedReceiptId(), ref.getResolution());
        }

        return buildResponse(receipt, draft);
    }

    private GoodsReceipt buildReceipt(ReceiptDraft draft, byte[] pdf, String filename,
                                      boolean reconciliationOk, Long userId) {
        var h = draft.getHeader();
        DraftSupplier supplier = draft.getSupplier();
        String supplierName = supplier.getExtracted() == null
                ? null : supplier.getExtracted().getName();

        return GoodsReceipt.builder()
                .supplierId(supplier.getMatchedSupplierId())
                .supplierNameSnapshot(supplierName)
                .invoiceNumber(h.getDocumentNumber().getValue())
                .orderNumber(h.getOrderNumber().getValue())
                .originalOrderNumber(h.getOriginalOrderNumber().getValue())
                .issueDate(h.getIssueDate().getValue())
                .dueDate(h.getDueDate().getValue())
                .taxableSupplyDate(h.getTaxableSupplyDate().getValue())
                .subtotal(h.getSubtotal().getValue())
                .vatAmount(h.getVatAmount().getValue())
                .totalAmount(h.getTotalAmount().getValue())
                .currency(h.getCurrency().getValue())
                .documentType(draft.getDocumentType())
                .sourceChannel(ReceiptSource.AI_PDF)
                .status(ReceiptStatus.PENDING_REVIEW)
                .reconciliationOk(reconciliationOk)
                .extractionModel(extractionModel)
                .sourceFilename(filename)
                .sourcePdf(pdf)
                .draftPayload(serializeDraft(draft))
                .createdBy(userId)
                .build();
    }

    private String serializeDraft(ReceiptDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JacksonException e) {
            throw new IllegalStateException("Serializace draftu příjemky selhala.", e);
        }
    }

    private ReceiptDraftDto.ImportResponse buildResponse(GoodsReceipt receipt, ReceiptDraft draft) {
        List<ReceiptDraftDto.CheckResult> checks = draft.getChecks().stream()
                .map(c -> ReceiptDraftDto.CheckResult.builder()
                        .code(c.getCode()).ok(c.isOk()).position(c.getPosition())
                        .build())
                .toList();

        List<ReceiptDraftDto.Line> items = draft.getLines().stream()
                .filter(l -> l.getLineKind() == DraftLine.LineKind.ITEM)
                .map(l -> ReceiptDraftDto.Line.builder()
                        .sku(l.getCatalogNumber().getValue())
                        .name(l.getName().getValue())
                        .quantity(l.getQuantity().getValue())
                        .unitPriceExclVat(l.getUnitPriceExclVat().getValue())
                        .vatRate(l.getVatRate().getValue())
                        .totalInclVat(l.getTotalInclVat().getValue())
                        .build())
                .toList();

        return ReceiptDraftDto.ImportResponse.builder()
                .receiptId(receipt.getId())
                .documentType(draft.getDocumentType().name())
                .status(receipt.getStatus().name())
                .documentNumber(receipt.getInvoiceNumber())
                .orderNumber(receipt.getOrderNumber())
                .supplierName(receipt.getSupplierNameSnapshot())
                .supplierMatched(draft.getSupplier().getMatchedSupplierId() != null)
                .reconciliationOk(Boolean.TRUE.equals(receipt.getReconciliationOk()))
                .totalAmount(receipt.getTotalAmount())
                .checks(checks)
                .items(items)
                .build();
    }
}
