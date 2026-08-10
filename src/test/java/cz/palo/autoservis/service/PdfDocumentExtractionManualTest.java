package cz.palo.autoservis.service;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.draft.DraftCheck;
import cz.palo.autoservis.model.draft.DraftLine;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANUÁLNÍ ověření AI extrakce na reálných PDF ze složky import/.
 * Volá skutečné Anthropic API — záměrně mimo CI.
 *
 * <p>Spuštění (vyžaduje ANTHROPIC_API_KEY v prostředí a lokální DB):
 * <pre>./mvnw test -Dtest=PdfDocumentExtractionManualTest -Dmanual.extraction=true</pre>
 *
 * <p>Účel: ověřit rizika structured outputu s tracked fields — písmenné sazby
 * (LKQ "C"), skupinové řádky "Dodací list č. X", poškozenou textovou vrstvu
 * (AUTO RAVIRA) a dodací list bez rekapitulace DPH. Výstup čti v konzoli.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "manual.extraction", matches = "true")
class PdfDocumentExtractionManualTest {

    @Autowired private PdfDocumentExtractionService extractionService;
    @Autowired private DraftAssembler assembler;
    @Autowired private DraftVerificationService verifier;

    @ParameterizedTest
    @CsvSource({
            "import/faktury/1726015934.pdf,INVOICE",        // LKQ: sazby písmenem C, řádkové totaly jen bez DPH
            "import/faktury/1726019023.pdf,INVOICE",        // LKQ: skupinový řádek 'Dodací list č. 3726025144'
            "import/faktury/FV202500684.pdf,INVOICE",       // AUTO RAVIRA: poškozená textová vrstva
            "import/dodaci_listy/3726026714.pdf,DELIVERY_NOTE", // LKQ DL: bez DPH rekapitulace
    })
    @DisplayName("extrakce + draft + kontroly nad reálným PDF")
    void extractRealPdf(String path, DocumentType type) throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of(path));

        DocumentExtractionResult extracted = extractionService.extract(pdf, type);
        ReceiptDraft draft = assembler.assemble(extracted, type, "manual-test");
        boolean reconciliationOk = verifier.verify(draft);

        System.out.println("=====================================================");
        System.out.println("PDF: " + path + " (" + type + ")");
        System.out.println("  doklad:    " + fieldInfo(draft.getHeader().getDocumentNumber()));
        System.out.println("  dodavatel: " + (draft.getSupplier().getExtracted() == null
                ? "—" : draft.getSupplier().getExtracted().getName()
                        + " IČO " + draft.getSupplier().getExtracted().getRegistrationNumber()));
        System.out.println("  subtotal:  " + fieldInfo(draft.getHeader().getSubtotal()));
        System.out.println("  vatAmount: " + fieldInfo(draft.getHeader().getVatAmount()));
        System.out.println("  total:     " + fieldInfo(draft.getHeader().getTotalAmount()));
        System.out.println("  rekonciliace: " + reconciliationOk);
        for (DraftCheck check : draft.getChecks()) {
            System.out.println("    check " + check.getCode()
                    + (check.getPosition() != null ? " [ř." + check.getPosition() + "]" : "")
                    + " → " + (check.isOk() ? "OK" : "FAIL"));
        }
        for (DraftLine line : draft.getLines()) {
            System.out.println("  " + line.getLineKind() + " ř." + line.getPosition()
                    + " | " + valueOf(line.getCatalogNumber())
                    + " | " + valueOf(line.getName())
                    + " | qty " + fieldInfo(line.getQuantity())
                    + " | DPH " + fieldInfo(line.getVatRate())
                    + " | s DPH " + fieldInfo(line.getTotalInclVat())
                    + (line.getDeliveryNoteNumber() != null ? " | DL " + line.getDeliveryNoteNumber() : ""));
        }

        assertThat(draft.getLines()).isNotEmpty();
        assertThat(draft.getHeader().getDocumentNumber().getValue()).isNotNull();
    }

    private String fieldInfo(cz.palo.autoservis.model.draft.TrackedField<?> f) {
        return f == null ? "null" : f.getValue() + " (" + f.getState() + ")";
    }

    private Object valueOf(cz.palo.autoservis.model.draft.TrackedField<?> f) {
        return f == null ? "—" : f.getValue();
    }
}
