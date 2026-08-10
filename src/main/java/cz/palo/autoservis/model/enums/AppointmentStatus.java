package cz.palo.autoservis.model.enums;

import java.util.EnumSet;
import java.util.Set;

/** Stav objednávky termínu – odpovídá typu ENUM {@code schedule.appointment_status} v PostgreSQL. */
public enum AppointmentStatus {
    /**
     * Termín domluvený se zákazníkem. Jediný výchozí stav — objednávka vzniká po telefonu
     * se zákazníkem na lince, takže je potvrzená už v okamžiku založení (V77).
     */
    PLANNED,
    /** Z objednávky vznikla zakázka – vazba je v {@code order_id}. */
    CONVERTED,
    /** Zákazník se nedostavil. */
    NO_SHOW,
    /** Objednávka zrušena, k práci nedojde. */
    CANCELLED;

    /**
     * Stavy, ve kterých se o termínu ještě rozhoduje — <strong>jediný</strong> vyjmenovaný seznam
     * automatu (týž princip jako {@link OrderStatus#canTransitionTo}). Terminalita se odvozuje jako
     * „není otevřený", takže nová hodnota v ENUMu bez rozhodnutí nic nepustí.
     */
    private static final Set<AppointmentStatus> OPEN = EnumSet.of(PLANNED);

    /**
     * @return {@code true} u stavu, ze kterého už objednávka nikam nepřechází
     *         ({@code CONVERTED}, {@code NO_SHOW}, {@code CANCELLED})
     */
    public boolean isTerminal() {
        return !OPEN.contains(this);
    }

    /**
     * Stavový automat objednávky termínu:
     * <pre>
     *   PLANNED → CONVERTED            (přijel, vznikla zakázka)
     *           → NO_SHOW              (nedorazil)
     *           → CANCELLED            (zavolal, že nepřijede)
     *   CONVERTED · NO_SHOW · CANCELLED → (terminální)
     * </pre>
     *
     * <h3>Proč jsou tři stavy terminální</h3>
     * <p>{@code CONVERTED} drží vazbu na vzniklou zakázku ({@code chk_appointments_converted_order});
     * návrat zpět by nechal osiřelé {@code order_id} nebo by rozbil ten CHECK. {@code NO_SHOW}
     * a {@code CANCELLED} jsou záznamy o tom, co se stalo — přepsat je zpět na „naplánováno" by
     * znamenalo přepisovat historii, ze které se počítá, kolik lidí nedorazilo.
     *
     * <p>Když je termín potřeba oživit, zakládá se nová objednávka. Původní zůstane v historii.
     *
     * <h3>Nezměněný stav není přechod</h3>
     * <p>Identita vrací {@code true}, aby úprava popisu u zrušené objednávky neselhala na automatu
     * (týž důvod jako u {@link OrderStatus}).
     *
     * <p>{@code CONVERTED} se nikdy nenastavuje touto cestou — vzniká výhradně v
     * {@code AppointmentServiceImpl.convert} spolu s {@code order_id}, aby se ty dvě hodnoty
     * nemohly rozejít.
     *
     * @param target cílový stav
     * @return {@code true}, je-li přechod z tohoto stavu na {@code target} povolený
     */
    public boolean canTransitionTo(AppointmentStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return !isTerminal();
    }
}
