package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CashReceiptMapper;
import cz.palo.autoservis.mapper.CompanyProfileMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.InvoicePartyMapper;
import cz.palo.autoservis.model.converter.CashReceiptConverter;
import cz.palo.autoservis.model.domain.billing.CashReceipt;
import cz.palo.autoservis.model.domain.billing.CompanyProfile;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.domain.billing.InvoiceSummary;
import cz.palo.autoservis.model.dto.billing.CashReceiptDto;
import cz.palo.autoservis.model.enums.CashReceiptNumberSource;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.service.CashReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementace {@link CashReceiptService}. Doklad se váže na vystavenou/zaplacenou fakturu;
 * číslo řady od V92 skládá aplikace podle masky z profilu firmy (týž mechanismus jako faktury
 * od V71: návrh MAX+1, editovatelné číslo, zámek řady, unikát). Částka a účel platby se
 * odvozují z faktury.
 */
@Service
@RequiredArgsConstructor
public class CashReceiptServiceImpl implements CashReceiptService {

    private final CashReceiptMapper cashReceiptMapper;
    private final InvoiceMapper invoiceMapper;
    private final InvoicePartyMapper invoicePartyMapper;
    private final CompanyProfileMapper companyProfileMapper;
    private final CashReceiptConverter cashReceiptConverter;

    @Override
    @Transactional
    public CashReceiptDto.DetailResponse createFromInvoice(CashReceiptDto.CreateRequest request, Long userId) {
        if (request == null || request.getInvoiceId() == null) {
            throw new IllegalArgumentException("invoiceId nesmí být null");
        }

        Invoice invoice = invoiceMapper.findById(request.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", request.getInvoiceId()));

        // Pokladní doklad potvrzuje úhradu existujícího dokladu. Koncept (DRAFT) ještě nemá číslo
        // ani není daňový doklad, stornovanou fakturu nemá smysl hradit — PPD jen k ISSUED/PAID.
        if (invoice.getStatus() != InvoiceStatus.ISSUED && invoice.getStatus() != InvoiceStatus.PAID) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_ISSUED", "invoiceId",
                    "pokladní doklad lze vystavit jen k vystavené nebo zaplacené faktuře, ne ke stavu "
                            + invoice.getStatus(),
                    Map.of("invoiceId", invoice.getId(), "status", invoice.getStatus()));
        }

        // Jeden platný pokladní doklad na fakturu (rozhodnutí uživatele 2026-07-30, audit KN-7).
        // Bez guardu vystaví dvojklik na tlačítko dva doklady na tutéž částku a pokladna vykáže
        // dvojnásobek přijaté hotovosti. Konflikt stavu → 409, ne 422: vstup je v pořádku,
        // jen se sráží se stavem zdroje. DB to jistí částečným unikátem (V68).
        cashReceiptMapper.findActiveByInvoiceId(invoice.getId()).ifPresent(existing -> {
            throw new ConflictException(
                    "CASH_RECEIPT_ALREADY_EXISTS",
                    "K faktuře " + invoice.getInvoiceNumber() + " už je vystaven pokladní doklad "
                            + existing.getReceiptNumber()
                            + ". Pokud vznikl omylem, nejdřív ho stornujte nebo smažte.");
        });

        InvoiceSummary summary = invoiceMapper.findSummaryByInvoiceId(invoice.getId())
                .orElseGet(() -> InvoiceSummary.zero(invoice.getId()));

        // Přijatá částka = „celkem k úhradě" z faktury. Zaokrouhlení na celé Kč (hotovost
        // nejmenší mincí 1 Kč, rozdíl mimo základ daně dle §36/5 ZDPH) počítá od V67 view
        // `v_invoice_price_totals` — jedno místo pro fakturu, PDF, QR i tenhle doklad.
        // Dřív si PPD zaokrouhloval sám a faktura zůstávala v haléřích, takže pokladna
        // a pohledávky se rozcházely (audit L-9/KN-7).
        BigDecimal amountReceived = summary.getTotalToPay();

        // Datum bez omezení, i budoucí (rozhodnutí uživatele 2026-08-09) — obsluha si doklad
        // připravuje dopředu, klidně den před příchodem zákazníka. Že hotovost opravdu přišla,
        // ručí obsluha; dřív 422 CASH_RECEIPT_ISSUE_DATE_IN_FUTURE.
        LocalDate issueDate = request.getIssueDate() != null ? request.getIssueDate() : LocalDate.now();

        // Zámek řady + pre-check unikátnosti = atomické vůči souběžnému vystavení
        // (vzor stampNumberAndIssue u faktur). Ruční číslo z cizí řady zámek obejde,
        // konečná pojistka je uq_cash_receipt_number.
        lockNumberSeriesFor(issueDate);
        String receiptNumber = requireUsableReceiptNumber(request.getReceiptNumber());

        CashReceipt receipt = CashReceipt.builder()
                .receiptNumber(receiptNumber)
                .invoiceId(invoice.getId())
                .issueDate(issueDate)
                .amount(amountReceived)
                .purpose(buildPurpose(invoice))
                .createdBy(userId)
                .build();
        try {
            cashReceiptMapper.insert(receipt);
        } catch (DuplicateKeyException e) {
            throw duplicateReceiptNumber(receiptNumber);
        }

        return getById(receipt.getId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nic nerezervuje — je to jen návrh pro předvyplnění dialogu. Souběh dvou uživatelů
     * se stejným návrhem vyřeší až zámek řady a unikátní constraint při vystavení.
     */
    @Override
    public CashReceiptDto.NextNumberResponse suggestNextNumber(LocalDate issueDate) {
        CashReceiptDto.NextNumberResponse response = new CashReceiptDto.NextNumberResponse();
        CompanyProfile company = companyProfileMapper.find().orElse(null);
        CashReceiptNumberSource source = company == null
                ? CashReceiptNumberSource.MANUAL
                : company.getCashReceiptNumberSource();
        response.setSource(source);
        if (source != CashReceiptNumberSource.MASK) {
            // INVOICE: číslo hrazené faktury zná FE (dialog ho předvyplní sám);
            // MANUAL: pole zůstává prázdné. Návrh dle řady se skládá jen pro MASK.
            return response;
        }

        LocalDate date = issueDate != null ? issueDate : LocalDate.now();
        DocumentNumberMask mask = parseMaskOrFail(company.getCashReceiptNumberMask());
        Long maxSequence = cashReceiptMapper.findMaxSequence(mask.regex(date));
        String suggestion = mask.format(date, (maxSequence == null ? 0L : maxSequence) + 1);

        if (suggestion.length() > DocumentNumberMask.MAX_NUMBER_LENGTH) {
            throw new BusinessRuleException(
                    "CASH_RECEIPT_NUMBER_SERIES_OVERFLOW", "receiptNumber",
                    "Řada přetekla — další číslo „" + suggestion + "“ přesahuje "
                            + DocumentNumberMask.MAX_NUMBER_LENGTH
                            + " znaků. Upravte masku ve Fakturačních údajích.",
                    Map.of("suggestion", suggestion, "mask", mask.source()));
        }

        response.setReceiptNumber(suggestion);
        return response;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Očekávaná čísla se skládají toutéž metodou, která je přiděluje ({@code mask.format})
     * — kontrola se nemůže s generátorem rozejít (týž princip jako u faktur, V89).
     */
    @Override
    public CashReceiptDto.NumberGapsResponse findNumberGaps() {
        CashReceiptDto.NumberGapsResponse response = new CashReceiptDto.NumberGapsResponse();

        CompanyProfile company = companyProfileMapper.find().orElse(null);
        if (company == null || !Boolean.TRUE.equals(company.getCashReceiptGapCheckEnabled())) {
            return response;   // enabled = false; prázdný seznam NEznamená „vše v pořádku"
        }
        // Režim INVOICE (V93): „díry" v řadě PPD jsou faktury zaplacené převodem, ne chyba —
        // kontrola dle masky by čísla faktur stejně nespárovala a mlčela by. Explicitně vypnuto;
        // souvislost řady hlídá kontrola mezer faktur (V89).
        if (company.getCashReceiptNumberSource() == CashReceiptNumberSource.INVOICE) {
            return response;
        }
        response.setEnabled(true);

        LocalDate today = LocalDate.now();
        response.setPeriodDate(today);
        DocumentNumberMask mask = parseMaskOrFail(company.getCashReceiptNumberMask());

        Long maxSequence = cashReceiptMapper.findMaxSequence(mask.regex(today));
        if (maxSequence == null) {
            return response;   // v období zatím nic — není mezi čím být mezera
        }

        // Startovní číslo platí, jen když patří do TOHOTO období (jinak by umlčelo celou řadu).
        long from = 1L;
        String configuredFrom = company.getCashReceiptGapCheckFrom();
        if (configuredFrom != null && !configuredFrom.isBlank()) {
            from = mask.sequenceOf(configuredFrom.trim(), today).orElse(1L);
        }

        Set<String> existing = new HashSet<>(cashReceiptMapper.findNumbersByRegex(mask.regex(today)));
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
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        cashReceiptMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pokladní doklad", id));

        // Tvrdé DELETE — vědomé rozhodnutí uživatele (2026-08-09): číslo se uvolní a MAX+1
        // ho příště nabídne znovu (bylo-li poslední), díru uprostřed zavře ruční zápis.
        // 0 řádků = souběžné smazání; výsledek je týž, není co hlásit.
        cashReceiptMapper.deleteById(id);
    }

    @Override
    @Transactional
    public CashReceiptDto.DetailResponse cancel(Long id, CashReceiptDto.CancelRequest request, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("důvod storna nesmí být prázdný");
        }

        CashReceipt receipt = cashReceiptMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pokladní doklad", id));

        // Doklad se nemaže (§35 ZoÚ) — zůstává v číselné řadě a jen přestane platit.
        // UPDATE je hlídaný stavem, takže druhé storno téhož dokladu změní 0 řádků;
        // tady se tomu jen dá srozumitelné jméno.
        if (cashReceiptMapper.cancel(id, request.getReason().strip(), userId) == 0) {
            throw new BusinessRuleException(
                    "CASH_RECEIPT_ALREADY_CANCELLED", "id",
                    "pokladní doklad " + receipt.getReceiptNumber() + " už je stornovaný",
                    Map.of("id", id));
        }

        return getById(id);
    }

    @Override
    public CashReceiptDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        CashReceipt receipt = cashReceiptMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pokladní doklad", id));
        return toDetail(receipt);
    }

    @Override
    public List<CashReceiptDto.DetailResponse> getByInvoiceId(Long invoiceId) {
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId nesmí být null");
        }
        return cashReceiptMapper.findByInvoiceId(invoiceId).stream()
                .map(this::toDetail)
                .toList();
    }

    /** Doplní k dokladu údaje odvozené z faktury (číslo/VS, strany, rozpis DPH, součty). */
    private CashReceiptDto.DetailResponse toDetail(CashReceipt receipt) {
        Long invoiceId = receipt.getInvoiceId();
        Invoice invoice = invoiceMapper.findById(invoiceId).orElse(null);

        return cashReceiptConverter.toDetailResponse(
                receipt,
                invoice,
                invoiceMapper.findSummaryByInvoiceId(invoiceId).orElse(null),
                invoiceMapper.findVatSummaryByInvoiceId(invoiceId),
                invoicePartyMapper.findByInvoiceId(invoiceId));
    }

    /** Účel platby dle §11 — na co byla hotovost přijata. */
    private static String buildPurpose(Invoice invoice) {
        StringBuilder sb = new StringBuilder("Úhrada faktury č. ").append(invoice.getInvoiceNumber());
        if (invoice.getVariableSymbol() != null) {
            sb.append(", VS ").append(invoice.getVariableSymbol());
        }
        return sb.toString();
    }

    /**
     * Poradní zámek nad řadou daného období (vzor lockNumberSeriesFor u faktur) — drží se
     * do konce transakce. Zamyká se jen v režimu MASK: v režimech INVOICE a MANUAL řadu
     * neskládá aplikace, čísla řídí obsluha a unikátnost jistí {@code uq_cash_receipt_number}.
     */
    private void lockNumberSeriesFor(LocalDate issueDate) {
        CompanyProfile company = companyProfileMapper.find().orElse(null);
        if (company == null || company.getCashReceiptNumberSource() != CashReceiptNumberSource.MASK) {
            return;
        }
        cashReceiptMapper.lockNumberSeries(
                parseMaskOrFail(company.getCashReceiptNumberMask()).regex(issueDate));
    }

    /** Neprázdnost + pre-check unikátnosti (finálně jistí {@code uq_cash_receipt_number}). */
    private String requireUsableReceiptNumber(String number) {
        String trimmed = number == null ? "" : number.trim();
        if (trimmed.isEmpty()) {
            // @NotBlank v DTO tohle normálně chytí dřív; tady jen pro jistotu přímých volání.
            throw new BusinessRuleException(
                    "CASH_RECEIPT_NUMBER_MISSING", "receiptNumber",
                    "Číslo pokladního dokladu je povinné.",
                    Map.of());
        }
        if (cashReceiptMapper.findByReceiptNumber(trimmed).isPresent()) {
            throw duplicateReceiptNumber(trimmed);
        }
        return trimmed;
    }

    private BusinessRuleException duplicateReceiptNumber(String number) {
        return new BusinessRuleException(
                "DUPLICATE_CASH_RECEIPT_NUMBER", "receiptNumber",
                "Pokladní doklad s číslem „" + number + "“ už existuje. Zvolte jiné číslo.",
                Map.of("receiptNumber", number));
    }

    /**
     * Neplatná maska = poškozené nastavení (UPDATE profilu ji validuje) — 422 s hláškou
     * parseru je lepší diagnóza než 500 (týž vzor jako u faktur).
     */
    private DocumentNumberMask parseMaskOrFail(String mask) {
        try {
            return DocumentNumberMask.parse(mask);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "INVALID_CASH_RECEIPT_NUMBER_MASK", "cashReceiptNumberMask",
                    "Maska číselné řady pokladních dokladů v nastavení není platná: " + e.getMessage(),
                    Map.of("mask", String.valueOf(mask)));
        }
    }
}
