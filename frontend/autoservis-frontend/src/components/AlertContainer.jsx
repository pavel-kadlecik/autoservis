import { createPortal } from "react-dom";
import { useAlert } from "../context/AlertContext.jsx";
import Alert from "./Alert.jsx";

/**
 * Sloupec toastů v pravém horním rohu.
 *
 * `z-index: 1080` je **nad** Bootstrapím modalem (1055): toast oznamuje výsledek
 * akce, kterou uživatel často spustil právě z dialogu. Pod dialogem by ho nikdo
 * neviděl a vypadalo by to, že se nestalo nic.
 *
 * Renderuje se **portálem do `document.body`** — stejně jako `Modal`. Jen tak
 * jsou toast i dialog ve **stejném** (kořenovém) stacking kontextu a porovnání
 * jejich z-indexů skutečně platí; dřív byl `AlertContainer` v `.app-shell`
 * a nad modalem se ocital jen shodou okolností v pořadí vykreslování.
 *
 * @param {number} time - po kolika ms toast sám zmizí
 */
export default function AlertContainer({ time }) {

    const { alerts, removeAlert } = useAlert();

    return createPortal(
        <div className="position-fixed bottom-0 end-0 p-2" style={{ zIndex: 1080 }}>
            {alerts.map(alert =>
                <Alert
                    key={alert.id}
                    type={alert.type}
                    message={alert.message}
                    time={time}
                    onClose={() => removeAlert(alert.id)}
                />)}
        </div>,
        document.body
    );
}
