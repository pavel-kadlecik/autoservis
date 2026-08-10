package cz.palo.autoservis.service.impl;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Sdílený převod HTML → PDF (openhtmltopdf) s vloženým Unicode fontem pro české znaky.
 * Používají ho tiskové služby faktury i opravného dokladu (E5.2) — dřív byl builder + registrace
 * fontů zkopírovaný v každé z nich.
 */
@Component
public class PdfRenderer {

    private static final String FONT_REGULAR = "/templates/fonts/DejaVuSans.ttf";
    private static final String FONT_BOLD    = "/templates/fonts/DejaVuSans-Bold.ttf";

    /**
     * Vyrenderuje HTML do PDF (A4).
     *
     * @param html         hotové HTML dokumentu
     * @param errorContext popis dokumentu do chybové hlášky (např. „faktura id=5")
     * @return PDF jako bajty
     */
    public byte[] htmlToPdf(String html, String errorContext) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerFonts(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Nepodařilo se vygenerovat PDF: " + errorContext, e);
        }
    }

    /**
     * Registruje vložený Unicode font, aby se české diakritiky vykreslily správně (Helvetica je
     * nepokrývá). Fonty jsou volitelné — chybí-li .ttf, PDF se přesto vyrobí (s horší diakritikou).
     */
    private void registerFonts(PdfRendererBuilder builder) {
        if (getClass().getResource(FONT_REGULAR) != null) {
            builder.useFont(() -> getClass().getResourceAsStream(FONT_REGULAR), "DejaVu Sans");
        }
        if (getClass().getResource(FONT_BOLD) != null) {
            builder.useFont(() -> getClass().getResourceAsStream(FONT_BOLD), "DejaVu Sans",
                    700, BaseRendererBuilder.FontStyle.NORMAL, true);
        }
    }
}
