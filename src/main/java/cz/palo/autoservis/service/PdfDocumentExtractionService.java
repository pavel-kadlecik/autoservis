package cz.palo.autoservis.service;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.dto.warehouse.DocumentExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Extrakce strukturovaných dat z PDF dokladu (faktura / dodací list) pomocí
 * Spring AI (Anthropic Claude).
 *
 * <p>PDF se modelu předá jako příloha application/pdf, cílový tvar popisuje
 * {@link DocumentExtractionResult}. Model u každého sledovaného pole přiznává
 * původ hodnoty (VERBATIM/DERIVED/ABSENT) — na VERIFIED povyšuje až
 * deterministický kód v DraftVerificationService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PdfDocumentExtractionService {

    private final ChatClient.Builder chatClientBuilder;

    private static final MimeType APPLICATION_PDF =
            MimeTypeUtils.parseMimeType("application/pdf");

    private static final String SYSTEM_PROMPT_CORE = """
            Jsi specialista na extrakci dat z dokladů dodavatelů autodílů
            (faktury a dodací listy).

            Pravidla pro hodnoty:
            - Čísla převeď do strojového formátu: desetinná tečka, žádné mezery
              (např. "6 520,66" -> 6520.66).
            - Názvy položek a čísla dokladů opisuj doslovně, nepřeformulovávej.
            - catalogNumber = katalogové/objednací číslo položky včetně případného
              prefixu značky (např. "EL 871.180", "SU001A3082").
            - position = pořadí řádku na dokladu, počítáno od 1.
            - Pokud je doklad fotka/sken, má PDF poškozenou textovou vrstvu
              (chybějící písmena), nebo je psaný RUKOU, čti údaje pečlivě
              vizuálně ze vzhledu stránky.
            - Když řádek uvádí jen cenu S DPH (typicky ručně psaný doklad má
              vyplněný jen sloupec "Cena celkem s DPH"), dej ji do totalInclVat
              a jednotkovou cenu (unitPriceExclVat) i základ (totalExclVat) nech
              ABSENT — dopočítá je kód. NEodhaduj je.

            Pravidla pro state (původ hodnoty) u každého sledovaného pole:
            - VERBATIM: hodnota je na dokladu doslova vytištěna.
            - DERIVED: hodnotu jsi dopočetl z jiných vytištěných hodnot
              (např. řádkový součet s DPH = množství x cena/ks s DPH, nebo
              sazba DPH odvozená z poměru ceny s DPH a bez DPH).
            - ABSENT: údaj na dokladu není a nelze ho spolehlivě dopočíst;
              value nech null. NIKDY si hodnoty nevymýšlej.

            Sazba DPH na řádku (vatRateOrCode): opiš PŘESNĚ to, co je na
            dokladu — procento ("21", "21%") nebo písmenný kód sazby ("C").
            Písmenné kódy NEPŘEVÁDĚJ na procenta; převodní tabulku sazeb vrať
            zvlášť ve vatRecap (code, ratePercent, base, vat), pokud je na
            dokladu rekapitulace DPH.

            Nepoložkové řádky v tabulce položek:
            - Řádek typu "Dodací list č. 3726025144 celkem 2 ks ..." je
              kind=DELIVERY_NOTE_GROUP: vyplň deliveryNoteNumber a případné
              součty; NENÍ to položka.
            - Jiné nepoložkové řádky (poznámky, mezisoučty) označ kind=NOTE.
              Sem patří i řádky za práci / servisní úkon / spotřební materiál
              (čistič, maziva, spojovací materiál), které nejsou skladovým dílem.
            """;

    private static final String SYSTEM_PROMPT_INVOICE = """

            Typ dokladu: FAKTURA (daňový doklad). Očekávej rekapitulaci DPH
            po sazbách, datum splatnosti, DUZP a celkovou částku s DPH.
            Chybí-li rozpis DPH, označ příslušná pole ABSENT — nevymýšlej je.
            """;

    private static final String SYSTEM_PROMPT_DELIVERY_NOTE = """

            Typ dokladu: DODACÍ LIST ("Není daňový doklad"). Rekapitulaci DPH,
            datum splatnosti, DUZP ani celkovou částku s DPH NEOČEKÁVEJ — pokud
            na dokladu nejsou, označ je ABSENT. Sazbu DPH řádku smíš odvodit
            (DERIVED) z poměru vytištěné ceny s DPH a bez DPH; jinak ABSENT.
            """;

    /** Zpětně kompatibilní varianta — doklad je PDF. */
    public DocumentExtractionResult extract(byte[] documentBytes, DocumentType documentType) {
        return extract(documentBytes, documentType, APPLICATION_PDF.toString());
    }

    /**
     * Extrahuje data z dokladu. Kromě PDF přijímá i **fotku nebo sken**
     * (rozhodnutí R-D) — model čte obrázek stejně jako stránku PDF, prompt už
     * vizuální čtení vyžaduje kvůli dokladům s poškozenou textovou vrstvou.
     *
     * @param documentBytes obsah souboru
     * @param documentType  typ dokladu zvolený uživatelem
     * @param mimeType      MIME typ nahraného souboru (PDF nebo obrázek)
     */
    public DocumentExtractionResult extract(byte[] documentBytes, DocumentType documentType,
                                            String mimeType) {
        MimeType resolved = mimeType == null || mimeType.isBlank()
                ? APPLICATION_PDF
                : MimeTypeUtils.parseMimeType(mimeType);
        Media pdf = new Media(resolved, new ByteArrayResource(documentBytes));

        String systemPrompt = SYSTEM_PROMPT_CORE
                + (documentType == DocumentType.DELIVERY_NOTE
                        ? SYSTEM_PROMPT_DELIVERY_NOTE
                        : SYSTEM_PROMPT_INVOICE);

        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(u -> u.text("Extrahuj všechna data z tohoto dokladu.").media(pdf))
                    .call()
                    .entity(DocumentExtractionResult.class);
        } catch (RuntimeException e) {
            // Selhání AI (timeout, přetížení, nevalidní JSON, chybějící klíč) → 503, ne 500 z catch-all
            // (E6.3). Uživatel dostane „zkuste znovu", ne „pád serveru".
            //
            // Příčinu logujeme UŽ TADY a s celým řetězcem výjimek. Handler ji sice loguje taky, ale
            // dostal se k němu jen text tohoto obalu, takže v logu stálo „Extrakci dokladu se
            // nepodařilo dokončit." a nic víc — tedy ani HTTP status od API, ani jméno modelu.
            // Import se pak nedal odladit: obsluha viděla „zkuste znovu", vývojář v logu totéž.
            log.warn("AI extrakce dokladu selhala (typ {}, mime {}, {} B): {}",
                    documentType, resolved, documentBytes.length, e.toString(), e);
            throw new cz.palo.autoservis.exception.DocumentExtractionException(
                    "Extrakci dokladu se nepodařilo dokončit.", e);
        }
    }
}
