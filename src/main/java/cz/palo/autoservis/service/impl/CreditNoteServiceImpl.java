package cz.palo.autoservis.service.impl;

import cz.palo.autoservis.exception.BusinessRuleException;
import cz.palo.autoservis.exception.ConflictException;
import cz.palo.autoservis.exception.ResourceNotFoundException;
import cz.palo.autoservis.mapper.CreditNoteMapper;
import cz.palo.autoservis.mapper.InvoiceMapper;
import cz.palo.autoservis.mapper.InvoicePartyMapper;
import cz.palo.autoservis.model.converter.CreditNoteConverter;
import cz.palo.autoservis.model.domain.billing.CreditNote;
import cz.palo.autoservis.model.domain.billing.Invoice;
import cz.palo.autoservis.model.dto.billing.CreditNoteDto;
import cz.palo.autoservis.model.enums.InvoiceStatus;
import cz.palo.autoservis.service.CreditNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * Implementace {@link CreditNoteService}. Dobropis se váže na vystavenou/zaplacenou fakturu;
 * číslo a §45 rozdíly viz {@link CreditNoteConverter} a DB trigger (V55).
 */
@Service
@RequiredArgsConstructor
public class CreditNoteServiceImpl implements CreditNoteService {

    private final CreditNoteMapper creditNoteMapper;
    private final InvoiceMapper invoiceMapper;
    private final InvoicePartyMapper invoicePartyMapper;
    private final CreditNoteConverter creditNoteConverter;

    @Override
    @Transactional
    public CreditNoteDto.DetailResponse createFromInvoice(CreditNoteDto.CreateRequest request, Long userId) {
        if (request == null || request.getOriginalInvoiceId() == null) {
            throw new IllegalArgumentException("originalInvoiceId nesmí být null");
        }

        Invoice original = invoiceMapper.findById(request.getOriginalInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Faktura", request.getOriginalInvoiceId()));

        // Opravit lze jen doklad, který byl vystaven (ISSUED/PAID). Koncept (DRAFT) se opravuje editací,
        // stornovanou (CANCELLED) fakturu opravovat nemá smysl (§45 se týká vystaveného dokladu).
        if (original.getStatus() != InvoiceStatus.ISSUED && original.getStatus() != InvoiceStatus.PAID) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_CORRECTABLE", "originalInvoiceId",
                    "opravný doklad lze vystavit jen k vystavené nebo zaplacené faktuře, ne ke stavu "
                            + original.getStatus(),
                    Map.of("invoiceId", original.getId(), "status", original.getStatus()));
        }

        // …a jen k dokladu, který zákazník DOSTAL (2026-08-08). Dobropis opravuje základ daně
        // nebo daň v CIZÍ evidenci — u faktury, která nikdy neodešla, není co opravovat:
        // odběratel ji nemá a odpočet z ní neuplatnil. Správná cesta je fakturu smazat
        // a vystavit znovu (V88), ne k ní vyrábět druhý doklad.
        //
        // Zaplacená faktura se za předanou považuje vždy — kdo platí, doklad má.
        if (original.getHandedOverAt() == null && original.getPaidAt() == null) {
            throw new BusinessRuleException(
                    "INVOICE_NOT_HANDED_OVER", "originalInvoiceId",
                    "Fakturu zákazník zatím nedostal, takže dobropis nemá co opravovat. "
                            + "Smažte ji a vystavte znovu správně. Pokud ji zákazník přesto má, "
                            + "označte ji nejdřív jako předanou.",
                    Map.of("invoiceId", original.getId()));
        }

        // Jeden aktivní opravný doklad na fakturu (audit KN-8). Každý dobropis nese CELOU
        // zápornou fakturu (MVP = plný dobropis, R-7), takže druhý by znamenal dvojnásobné
        // snížení daně na výstupu a zápornou pohledávku. Guard je tu kvůli srozumitelné hlášce;
        // poslední slovo má částečný unikát `uq_credit_notes_original_active` (V66), který
        // udrží pravidlo i při souběhu dvou požadavků.
        creditNoteMapper.findByOriginalInvoiceId(original.getId()).stream()
                .filter(existing -> existing.getStatus() != InvoiceStatus.CANCELLED)
                .findFirst()
                .ifPresent(existing -> {
                    throw new BusinessRuleException(
                            "INVOICE_ALREADY_CREDITED", "originalInvoiceId",
                            "K faktuře " + original.getInvoiceNumber() + " už opravný daňový doklad "
                                    + "existuje" + (existing.getCreditNoteNumber() == null
                                            ? " (zatím jako koncept)"
                                            : " č. " + existing.getCreditNoteNumber()) + ".",
                            Map.of("invoiceId", original.getId(), "creditNoteId", existing.getId()));
                });

        CreditNote creditNote = creditNoteConverter.toDomain(request);
        creditNote.setCreatedBy(userId);
        creditNote.setStatus(InvoiceStatus.DRAFT);
        creditNote.setIssueDate(LocalDate.now());
        if (creditNote.getTaxableSupplyDate() == null) {
            creditNote.setTaxableSupplyDate(creditNote.getIssueDate());
        }
        creditNoteMapper.insert(creditNote);

        return getById(creditNote.getId());
    }

    @Override
    @Transactional
    public CreditNoteDto.DetailResponse issue(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        CreditNote creditNote = creditNoteMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opravný doklad", id));

        if (creditNote.getStatus() != InvoiceStatus.DRAFT) {
            throw new BusinessRuleException(
                    "INVALID_STATUS_TRANSITION", "creditNote",
                    "opravný doklad " + id + " už není koncept (stav " + creditNote.getStatus() + ")",
                    Map.of("creditNoteId", id, "status", creditNote.getStatus()));
        }

        // Guardovaný přechod (jako faktura); 0 řádků = stav mezitím změněn.
        int affected = creditNoteMapper.updateStatus(id, InvoiceStatus.ISSUED, InvoiceStatus.DRAFT);
        if (affected == 0) {
            throw new ConflictException("CREDIT_NOTE_STATE_CHANGED",
                    "Opravný doklad " + id + " mezitím změnil někdo jiný. Načtěte ho znovu.");
        }

        // Vystavením dobropisu přestává být původní faktura aktivní fakturou zakázky —
        // zakázku lze fakturovat znovu (V69). Razítko patří sem, ne k založení konceptu:
        // koncept dobropisu se ještě nikam neodeslal a nic neopravuje. Faktura si stav
        // ISSUED/PAID ponechá, protože vystaveným dokladem být nepřestala.
        invoiceMapper.markCredited(creditNote.getOriginalInvoiceId());

        return getById(id);
    }

    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        CreditNote creditNote = creditNoteMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opravný doklad", id));

        if (creditNote.getStatus() != InvoiceStatus.DRAFT) {
            throw new BusinessRuleException(
                    "CREDIT_NOTE_NOT_DELETABLE", "creditNote",
                    "Smazat lze jen koncept opravného dokladu — vystavený doklad "
                            + creditNote.getCreditNoteNumber() + " je platný daňový doklad.",
                    Map.of("creditNoteId", id, "status", creditNote.getStatus()));
        }

        // Guarded write (vzor K5): 0 řádků = koncept mezitím někdo vystavil → 409.
        // Razítko `credited_at` na faktuře se neřeší — to dává až vystavení dobropisu,
        // takže koncept po sobě na faktuře nic nenechává.
        if (creditNoteMapper.deleteDraft(id) == 0) {
            throw new ConflictException("CREDIT_NOTE_STATE_CHANGED",
                    "Opravný doklad " + id + " mezitím změnil někdo jiný. Načtěte ho znovu.");
        }
    }

    @Override
    public java.util.List<CreditNoteDto.DetailResponse> getByInvoiceId(Long invoiceId) {
        if (invoiceId == null) {
            throw new IllegalArgumentException("invoiceId nesmí být null");
        }
        return creditNoteMapper.findByOriginalInvoiceId(invoiceId).stream()
                .map(creditNote -> getById(creditNote.getId()))
                .toList();
    }

    @Override
    public CreditNoteDto.DetailResponse getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id nesmí být null");
        }
        CreditNote creditNote = creditNoteMapper.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Opravný doklad", id));

        Long invoiceId = creditNote.getOriginalInvoiceId();
        Invoice original = invoiceMapper.findById(invoiceId).orElse(null);

        return creditNoteConverter.toDetailResponse(
                creditNote,
                original,
                invoiceMapper.findSummaryByInvoiceId(invoiceId).orElse(null),
                invoiceMapper.findVatSummaryByInvoiceId(invoiceId),
                invoicePartyMapper.findByInvoiceId(invoiceId));
    }
}
