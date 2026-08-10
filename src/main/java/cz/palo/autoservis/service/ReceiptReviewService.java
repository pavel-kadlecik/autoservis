package cz.palo.autoservis.service;

import cz.palo.autoservis.model.domain.warehouse.GoodsReceipt;
import cz.palo.autoservis.model.draft.ReceiptDraft;
import cz.palo.autoservis.model.dto.pagination.PagedResponse;
import cz.palo.autoservis.model.dto.warehouse.ReceiptDto;
import cz.palo.autoservis.model.dto.warehouse.ReceiptSearchParams;

/**
 * Review workflow příjemek: seznam, detail, editace draftu a potvrzení /
 * zamítnutí. Potvrzení je jediné místo, kde z draftu vznikají dodavatelé,
 * produkty, šarže a pohyby RECEIPT.
 */
public interface ReceiptReviewService {

    PagedResponse<ReceiptDto.ListResponse> list(ReceiptSearchParams params);

    /**
     * Založí prázdný draft ruční příjemky (source_channel MANUAL, bez PDF).
     * Ruční vkládání = kontrolní obrazovka nad prázdným draftem — žádný
     * druhý formulář ani druhá cesta kódu.
     */
    ReceiptDto.DetailResponse createManualDraft(ReceiptDto.CreateDraftRequest request, Long userId);

    ReceiptDto.DetailResponse getDetail(Long id);

    /** Příjemka s naplněným source_pdf (404, pokud PDF neexistuje — např. ruční draft). */
    GoodsReceipt getPdf(Long id);

    /**
     * Uloží editovaný draft (stavy EDITED nastavuje frontend u polí, která
     * uživatel změnil), znovu spustí deterministické kontroly a synchronizuje
     * hlavičkovou projekci. Jen pro PENDING_REVIEW.
     */
    ReceiptDto.DetailResponse updateDraft(Long id, ReceiptDraft draft, Long userId);

    /**
     * Potvrdí příjemku: completeness gate → vyřešení dodavatele (match/insert)
     * → materializace produktů, šarží a pohybů RECEIPT → status CONFIRMED.
     * Guarded proti souběhu (dvojí potvrzení → ConflictException).
     */
    ReceiptDto.DetailResponse confirm(Long id, Long userId);

    /** Zamítne příjemku — nic se nematerializuje, číslo dokladu se uvolní. */
    ReceiptDto.DetailResponse reject(Long id, String note, Long userId);

    /**
     * Stornuje <b>potvrzenou</b> příjemku (V43, rozhodnutí R-C): ke každé šarži
     * zapíše kompenzační pohyb (ledger zůstává append-only, nic se nemaže) a doklad
     * přejde do CANCELLED, čímž uvolní své číslo pro opravný import.
     *
     * <p>Povoleno jen dokud se z příjemky nečerpalo — čerpaná šarže nebo vazba
     * z položky zakázky vede na 422 {@code RECEIPT_ALREADY_USED}; takovou chybu
     * je nutné řešit ruční korekcí, ne stornem celého dokladu.
     */
    ReceiptDto.DetailResponse cancel(Long id, String note, Long userId);
}
