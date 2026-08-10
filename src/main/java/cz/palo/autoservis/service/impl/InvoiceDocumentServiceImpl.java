package cz.palo.autoservis.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import cz.palo.autoservis.model.dto.billing.InvoiceDto;
import cz.palo.autoservis.service.InvoiceDocumentService;
import cz.palo.autoservis.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InvoiceDocumentServiceImpl implements InvoiceDocumentService {

    private static final String TEMPLATE = "pdf/invoice";

    private final InvoiceService invoiceService;
    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;
    private final SpaydBuilder spaydBuilder;

    @Override
    public byte[] renderPdf(Long invoiceId) {
        return pdfRenderer.htmlToPdf(renderHtml(invoiceId), "faktura id=" + invoiceId);
    }

    /** Vyrenderuje šablonu faktury do HTML řetězce (mezikrok pro PDF). */
    private String renderHtml(Long invoiceId) {
        InvoiceDto.DetailResponse invoice = invoiceService.getById(invoiceId);

        Context context = new Context();
        context.setVariable("invoice", invoice);
        context.setVariable("qrDataUri", buildPaymentQr(invoice));
        context.setVariable("logoDataUri", loadLogoDataUri());
        context.setVariable("signature", loadSignature());


        return templateEngine.process(TEMPLATE, context);
    }

    /**
     * Sestaví PNG „QR Platby" (SPAYD) jako data URI pro fakturu, nebo {@code null},
     * když není co platit QR kódem (koncept bez čísla, nebo dodavatel bez IBAN).
     * SPAYD řetězec staví a testuje {@link SpaydBuilder}.
     */
    private String buildPaymentQr(InvoiceDto.DetailResponse invoice) {
        String spayd = spaydBuilder.build(invoice);
        return spayd == null ? null : toQrDataUri(spayd);
    }

    /** Vyrenderuje řetězec do PNG QR kódu a vrátí ho jako base64 data URI. */
    private String toQrDataUri(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", os);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(os.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Nepodařilo se vygenerovat QR kód platby", e);
        }
    }

    private String loadLogoDataUri() {
        try {
            byte[] bytes = getClass().getResourceAsStream("/templates/images/logo.png").readAllBytes();
            String logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            return logoDataUri;
        }catch (Exception e){
            throw new IllegalStateException("Nepodařilo se načíst logo", e);
        }
    }

    private String loadSignature() {
        try {
            byte[] bytes = getClass().getResourceAsStream("/templates/images/signature.png").readAllBytes();
            String logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            return logoDataUri;
        }catch (Exception e){
            throw new IllegalStateException("Nepodařilo se načíst podpis", e);
        }
    }
}
