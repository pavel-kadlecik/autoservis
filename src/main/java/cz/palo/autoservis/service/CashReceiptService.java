package cz.palo.autoservis.service;

import cz.palo.autoservis.model.dto.billing.CashReceiptDto;

import java.time.LocalDate;
import java.util.List;

/**
 * Příjmový pokladní doklad (PPD) — vytvoření k faktuře, detail, seznam k faktuře.
 * Částka a účel platby se odvozují z faktury (server počítá); číslo řady od V92 skládá
 * aplikace podle masky z profilu firmy (uživatel ho v dialogu může přepsat).
 */
public interface CashReceiptService {

    /**
     * Vystaví pokladní doklad k dané faktuře. Přijatá částka = částka k úhradě z faktury
     * ({@code totalToPay}, u hotovosti zaokrouhlená — V67), účel platby se složí z čísla faktury a VS.
     * Číslo dokladu dodává request (návrh z {@link #suggestNextNumber}); unikátnost hlídá
     * pre-check + {@code uq_cash_receipt_number} pod zámkem řady.
     *
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException faktura neexistuje
     * @throws cz.palo.autoservis.exception.BusinessRuleException     faktura ještě není vystavená (koncept/storno),
     *                                                                duplicitní číslo dokladu
     * @throws cz.palo.autoservis.exception.ConflictException         k faktuře už platný doklad existuje (409)
     */
    CashReceiptDto.DetailResponse createFromInvoice(CashReceiptDto.CreateRequest request, Long userId);

    /**
     * Návrh dalšího čísla řady pro dané datum (MAX+1 podle masky). Nic nerezervuje —
     * souběh dvou stejných návrhů vyřeší zámek řady a unikát při vystavení.
     */
    CashReceiptDto.NextNumberResponse suggestNextNumber(LocalDate issueDate);

    /** Chybějící čísla aktuálního období řady PPD (V92) — jen informuje, nic nevynucuje. */
    CashReceiptDto.NumberGapsResponse findNumberGaps();

    /**
     * Smaže pokladní doklad — vědomé rozhodnutí uživatele (2026-08-09): řadu si obsluha řídí
     * sama, číslo se uvolní a faktura přestane mít navázaný doklad (lze ji pak vrátit do
     * konceptu). Mazat jde i stornovaný doklad. Kdo chce záznam zachovat, použije storno.
     *
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException doklad neexistuje
     */
    void delete(Long id);

    /**
     * Stornuje pokladní doklad vystavený omylem. Doklad zůstává v číselné řadě (§35 ZoÚ — účetní
     * záznam se nemaže), jen přestane platit; teprve pak lze k faktuře vystavit nový.
     *
     * @throws cz.palo.autoservis.exception.ResourceNotFoundException doklad neexistuje
     * @throws cz.palo.autoservis.exception.BusinessRuleException     doklad je už stornovaný
     */
    CashReceiptDto.DetailResponse cancel(Long id, CashReceiptDto.CancelRequest request, Long userId);

    /** Detail pokladního dokladu vč. účastníků a rozpisu DPH odvozených z faktury. */
    CashReceiptDto.DetailResponse getById(Long id);

    /** Pokladní doklady vystavené k dané faktuře (může být prázdné). */
    List<CashReceiptDto.DetailResponse> getByInvoiceId(Long invoiceId);
}
