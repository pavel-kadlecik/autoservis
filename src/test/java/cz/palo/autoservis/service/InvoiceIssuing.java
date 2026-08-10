package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.InvoiceDto;

import java.time.LocalDate;

/**
 * Pomocník pro testy, které fakturu jen potřebují mít vystavenou a číslo samo neřeší.
 *
 * <p>Číslo a datum vystavení dostává doklad až při vystavení a request nese obojí (stejně
 * jako dialog vystavení, který číslo předvyplní návrhem řady a datum vezme z konceptu).
 * Bez tohoto pomocníka by se ve zhruba padesáti voláních opakovaly tři řádky, které
 * s předmětem těch testů nesouvisí.
 *
 * <p>Testy, které číslování testují (ruční číslo, duplicita, mezery v řadě), si request
 * staví samy — ať je z nich vidět, co přesně posílají.
 */
public final class InvoiceIssuing {

    private InvoiceIssuing() {
    }

    /**
     * Vystaví koncept datem, které koncept nese, a číslem z řady téhož období — přesně jako
     * dialog vystavení. Server datum nepřepisuje (rozhodnutí uživatele 2026-08-07), takže
     * datum i číslo musí do requestu dát volající.
     */
    public static InvoiceDto.DetailResponse issueWithNextNumber(InvoiceService invoiceService, Long invoiceId, Long userId) {
        LocalDate issueDate = invoiceService.getById(invoiceId).getIssueDate();
        return invoiceService.issue(invoiceId, nextNumberRequest(invoiceService, issueDate), userId);
    }

    /** Request na vystavení s číslem podle návrhu řady dnešního období a bez variabilního symbolu. */
    public static InvoiceDto.IssueRequest nextNumberRequest(InvoiceService invoiceService) {
        return nextNumberRequest(invoiceService, LocalDate.now());
    }

    /** Request na vystavení s číslem podle návrhu řady <strong>daného období</strong>. */
    public static InvoiceDto.IssueRequest nextNumberRequest(InvoiceService invoiceService, LocalDate issueDate) {
        return requestFor(invoiceService.suggestNextNumber(issueDate).getInvoiceNumber(), issueDate);
    }

    /** Request na vystavení s konkrétním ručním číslem a dnešním datem vystavení. */
    public static InvoiceDto.IssueRequest requestFor(String invoiceNumber) {
        return requestFor(invoiceNumber, LocalDate.now());
    }

    /** Request na vystavení s konkrétním ručním číslem a konkrétním datem vystavení. */
    public static InvoiceDto.IssueRequest requestFor(String invoiceNumber, LocalDate issueDate) {
        InvoiceDto.IssueRequest request = new InvoiceDto.IssueRequest();
        request.setInvoiceNumber(invoiceNumber);
        request.setIssueDate(issueDate);
        return request;
    }
}
