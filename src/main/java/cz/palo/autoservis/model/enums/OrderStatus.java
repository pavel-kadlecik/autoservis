package cz.palo.autoservis.model.enums;

import java.util.EnumSet;
import java.util.Set;

/** Stav životního cyklu servisní zakázky — mapuje se na PostgreSQL ENUM {@code order.order_status}. */
public enum OrderStatus {
    RECEIVED,
    DIAGNOSIS,
    WAITING_FOR_PARTS,
    IN_PROGRESS,
    READY_FOR_PICKUP,
    COMPLETED,
    CANCELLED;

    /**
     * Provozní stavy zakázky — <strong>jediný</strong> vyjmenovaný seznam automatu.
     *
     * <p>Terminální stav je definován jako „není provozní ani vratný" (viz {@link #isTerminal()}),
     * a to záměrně: nová hodnota v ENUMu, o které nikdo nerozhodl, tím z automatu nic nepustí
     * a chyba se ozve hned, místo aby se tiše povolily všechny přechody.
     */
    private static final Set<OrderStatus> OPERATIONAL = EnumSet.of(
            RECEIVED, DIAGNOSIS, WAITING_FOR_PARTS, IN_PROGRESS, READY_FOR_PICKUP);

    /**
     * Stavy uzavřené, ale <strong>vratné</strong> — zakázku z nich lze znovu otevřít.
     *
     * <p>{@code COMPLETED} sem přibyl 2026-08-06. Do té doby byl terminální, takže omylem
     * kliknuté „Dokončena" bylo v celé aplikaci nevratné: zakázka nešla vrátit do provozu
     * ani zrušit a smazat se nedá vůbec. Odůvodnění „návrat by odemkl editaci položek"
     * navíc neplatilo — položky zamyká faktura, ne stav zakázky.
     */
    private static final Set<OrderStatus> REOPENABLE = EnumSet.of(COMPLETED);

    /**
     * @return {@code true} u stavu, ze kterého už zakázka nikam nepřechází — nově jen
     *         {@code CANCELLED}
     */
    public boolean isTerminal() {
        return !OPERATIONAL.contains(this) && !REOPENABLE.contains(this);
    }

    /**
     * @return {@code true} u stavu, ze kterého jde zakázku <strong>znovu otevřít</strong>
     *         ({@code COMPLETED})
     *
     * <p>Rozlišuje návrat do provozu od ostatních přechodů: znovuotevření má vlastní
     * podmínku (zakázka nesmí mít aktivní fakturu) a vrací vydaný materiál do rezervace.
     */
    public boolean isReopenable() {
        return REOPENABLE.contains(this);
    }

    /**
     * Stavový automat zakázky (audit KN-11, rozhodnutí uživatele 2026-07-30;
     * COMPLETED vratný od 2026-08-06):
     * <pre>
     *   RECEIVED · DIAGNOSIS · WAITING_FOR_PARTS · IN_PROGRESS · READY_FOR_PICKUP
     *        → libovolně mezi sebou, oběma směry
     *        → COMPLETED nebo CANCELLED
     *   COMPLETED → (vratný — znovuotevření, podmínky v OrderServiceImpl)
     *   CANCELLED → (terminální)
     * </pre>
     *
     * <h3>Proč je pohyb mezi provozními stavy volný</h3>
     * <p>Servis reálně skáče dozadu: díl přijde poškozený, takže se z {@code IN_PROGRESS} vrací na
     * {@code WAITING_FOR_PARTS}; diagnostika se otevírá znovu. Automat, který by vynucoval jediné
     * pořadí, by obsluhu nutil lhát o stavu vozu. Zakázáno je proto jen to, co je věcně nevratné.
     *
     * <h3>Proč je CANCELLED terminální</h3>
     * <p>Z {@code CANCELLED} zpět by oživilo zakázku, jejíž materiál se už vrátil na sklad.
     * Do zavedení automatu šlo jedním PUT přepnout na {@code CANCELLED} i zakázku s vystavenou
     * fakturou a {@code CANCELLED → RECEIVED} prošlo také. ({@code COMPLETED} byl terminální
     * do 2026-08-06 — dnes je vratný, viz {@link #isReopenable()}: položky zamyká faktura,
     * ne stav zakázky.)
     *
     * <h3>Nezměněný stav není přechod</h3>
     * <p>{@code PUT /orders/{id}} nese celý záznam včetně stavu, takže i oprava překlepu v popisu
     * hotové zakázky přijde se stavem {@code COMPLETED}. Identita proto vrací {@code true} —
     * terminalita znamená „žádný návrat do provozu", ne zámek celého záznamu (rozhodnutí uživatele
     * 2026-07-31). Zámek editace <em>položek</em> drží vystavená faktura, ne stav zakázky.
     *
     * <p>Doplňkové podmínky, které automat sám neunese, protože závisejí na stavu databáze, jsou
     * v {@code OrderServiceImpl.update}: do {@code CANCELLED} nesmí zakázka s aktivní fakturou ani
     * s materiálem, který drží skladovou šarži.
     *
     * @param target cílový stav
     * @return {@code true}, je-li přechod z tohoto stavu na {@code target} povolený
     */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return !isTerminal();
    }
}
