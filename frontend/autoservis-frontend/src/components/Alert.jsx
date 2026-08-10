import { useEffect, useState } from "react";

/**
 * Jeden toast v pravém horním rohu. Vrstvení řeší {@link AlertContainer} —
 * tahle komponenta si `z-index` nenastavuje. Dřív měla `zIndex: 11`, což ji
 * schovalo pod modal (Bootstrap dává dialogu 1055).
 *
 * @param {string}   message
 * @param {"success"|"danger"|"info"} type
 * @param {number}   time     - po kolika ms sám zmizí
 * @param {Function} onClose
 */
export default function Alert({ message, type, time, onClose }) {

    const [visible, setVisible] = useState(true);

    useEffect(() => {
        const timer = setTimeout(() => setVisible(false), time);
        return () => clearTimeout(timer);
    }, [time]);

    if (!visible) return null;

    return (
        <div className={`alert alert-${type} alert-dismissible fade show`} role="alert">
            {message}
            <button type="button" className="btn-close" aria-label="Zavřít"
                    onClick={() => { setVisible(false); onClose(); }}></button>
        </div>
    );
}
