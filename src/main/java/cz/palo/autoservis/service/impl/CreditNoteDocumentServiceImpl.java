package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.service.CreditNoteDocumentService;
import cz.palo.autoservis.service.CreditNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renderuje PDF opravného dokladu (§45): Thymeleaf šablona {@code pdf/credit-note} + sdílený
 * {@link PdfRenderer}. Data (rozdíly, strany, čísla dokladů) nese {@code CreditNoteService.getById}.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteDocumentServiceImpl implements CreditNoteDocumentService {

    private static final String TEMPLATE = "pdf/credit-note";

    private final CreditNoteService creditNoteService;
    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    @Override
    public byte[] renderPdf(Long creditNoteId) {
        CreditNoteDto.DetailResponse creditNote = creditNoteService.getById(creditNoteId);

        Context context = new Context();
        context.setVariable("cn", creditNote);

        String html = templateEngine.process(TEMPLATE, context);
        return pdfRenderer.htmlToPdf(html, "opravný doklad id=" + creditNoteId);
    }
}
