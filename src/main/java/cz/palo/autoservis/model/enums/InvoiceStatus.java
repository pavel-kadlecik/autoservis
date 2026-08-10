package cz.palo.autoservis.model.enums;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Stav životního cyklu faktury — mapuje se na PostgreSQL ENUM {@code billing.invoice_status}. */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PAID,
    CANCELLED;

    /**
     * Povolené přechody stavů (stavový automat faktury):
     * <pre>
     *   DRAFT     → ISSUED
     *   ISSUED    → PAID
     *   PAID      → (terminální)
     *   CANCELLED → (terminální, jen historická data)
     * </pre>
     *
     * <h3>Proč se do CANCELLED už nedá dostat (rozhodnutí uživatele 2026-08-02)</h3>
     * <p>Storno konceptu nahradilo <strong>mazání</strong>: koncept není doklad — nemá číslo,
     * nikdy neopustil firmu — takže není co archivovat a stornované koncepty jen zaplňovaly
     * tabulku. Přechod {@code DRAFT → CANCELLED} tím zanikl a hodnota {@code CANCELLED} zůstává
     * pouze kvůli <strong>historickým řádkům</strong>: stavem se dál filtruje
     * ({@code uq_invoices_order_active}, {@code findByOrderId}), ale aplikace ho už nikdy
     * nenastaví. Mazat data starých storen by bylo v rozporu s R-06.
     *
     * <h3>Proč ISSUED → CANCELLED nebylo povoleno ani předtím (audit KN-1, 2026-07-30)</h3>
     * <p>Vystavenou fakturu, kterou zákazník dostal a která je vykázaná v přiznání k DPH, nelze
     * zrušit a vystavit znovu; §42 a §45 zákona o DPH na opravu předepisují <strong>opravný daňový
     * doklad</strong> ({@code billing.credit_notes}). Omylem vystavenou fakturu tedy nejde
     * „zahodit" — řeší se dobropisem. Zamčení bylo provedeno až poté, co byl dobropis dostupný
     * z aplikace; jinak by jedna slepá ulička nahradila druhou.
     *
     * <h3>Proč {@code PAID} zůstává terminální i po zavedení „vzít platbu zpět" (2026-08-08)</h3>
     * <p>Tahle mapa popisuje <strong>posun dokladu vpřed</strong> — vystavení a úhradu. Vzetí
     * platby zpět ({@code InvoiceServiceImpl.revokePayment}) sem <em>nepatří</em>: není to
     * posun dokladu, ale oprava interní evidence úhrady. Číslo ani datum vystavení se nemění.
     *
     * <p>Pokus zapsat ho jako přechod {@code PAID → ISSUED} byl vrácen, protože tím zároveň
     * zpřístupnil {@code transitionTo(id, ISSUED)}, tedy cestu <strong>znovu vystavit
     * zaplacenou fakturu</strong> a přidělit jí nové číslo. Odhalil to test
     * {@code paid_isTerminal}. Vzetí platby proto jede vlastním guardovaným UPDATE
     * (`clearPayment` s `AND status = 'PAID'`), který tuhle díru neotevírá.
     */
    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED_TRANSITIONS = Map.of(
            DRAFT,     EnumSet.of(ISSUED),
            ISSUED,    EnumSet.of(PAID),
            PAID,      EnumSet.noneOf(InvoiceStatus.class),
            CANCELLED, EnumSet.noneOf(InvoiceStatus.class)
    );

    /**
     * @param target cílový stav
     * @return {@code true}, je-li přechod z tohoto stavu na {@code target} povolený
     */
    public boolean canTransitionTo(InvoiceStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(InvoiceStatus.class)).contains(target);
    }

    /**
     * @return {@code true}, smí-li se faktuře v tomto stavu editovat pole a položky
     *         (editovatelný je jen {@code DRAFT} — vystavená faktura je neměnný doklad)
     */
    public boolean isEditable() {
        return this == DRAFT;
    }
}
