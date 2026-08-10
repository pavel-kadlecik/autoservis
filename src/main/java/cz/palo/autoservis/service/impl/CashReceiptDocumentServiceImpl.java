package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.model.dto.billing.CashReceiptDto;
import cz.palo.autoservis.service.CashReceiptDocumentService;
import cz.palo.autoservis.service.CashReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Base64;

/**
 * Renderuje PDF příjmového pokladního dokladu: Thymeleaf šablona {@code pdf/cash-receipt} + sdílený
 * {@link PdfRenderer}. Data (částka, částka slovy, účel, strany, rozpis DPH) nese {@code CashReceiptService.getById}.
 */
@Service
@RequiredArgsConstructor
public class CashReceiptDocumentServiceImpl implements CashReceiptDocumentService {

    private static final String TEMPLATE = "pdf/cash-receipt";

    private final CashReceiptService cashReceiptService;
    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    @Override
    public byte[] renderPdf(Long cashReceiptId) {
        CashReceiptDto.DetailResponse receipt = cashReceiptService.getById(cashReceiptId);

        Context context = new Context();
        context.setVariable("r", receipt);
        context.setVariable("logoDataUri", loadImage("/templates/images/logo.png", "logo"));
        context.setVariable("signature", loadImage("/templates/images/signature.png", "podpis"));

        String html = templateEngine.process(TEMPLATE, context);
        return pdfRenderer.htmlToPdf(html, "pokladní doklad id=" + cashReceiptId);
    }

    /** Načte obrázek z classpath jako base64 data-URI (razítko a logo se vkládají přímo do HTML). */
    private String loadImage(String resource, String label) {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Zdroj nenalezen: " + resource);
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Nepodařilo se načíst " + label, e);
        }
    }
}
