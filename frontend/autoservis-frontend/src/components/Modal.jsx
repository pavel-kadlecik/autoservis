import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";

/**
 * Jediné místo v aplikaci, kde se staví modální okno. Dřív mělo devět komponent
 * vlastní kopii `.modal show d-block` s inline backdropem — žádná nereagovala na
 * Esc, nezamykala scroll pozadí a neměla focus trap.
 *
 * Renderuje se **přes portál do `document.body`**. Bez toho dialog vykreslený
 * z komponenty uvnitř stacking kontextu (např. `ChangePasswordModal` v sidebaru,
 * který má `position: sticky`) zůstal uvězněný v tom kontextu a jeho `z-index`
 * neplatil vůči obsahu stránky — modal se schoval ZA karty formuláře. Portál ho
 * vytáhne na úroveň body, kde `z-index` konečně platí globálně.
 *
 * Bootstrap JS API se záměrně nepoužívá (projekt ho pro modaly nikdy nepoužíval),
 * jen jeho CSS třídy.
 *
 * @param {boolean}         show
 * @param {string}          title
 * @param {string}          [size]     - „modal-lg“ / „modal-sm“; bez ní výchozí šířka
 * @param {Function}        onClose    - zavření křížkem, Escapem nebo klikem na pozadí
 * @param {React.ReactNode} children   - tělo dialogu
 * @param {React.ReactNode} [footer]   - tlačítka; pořadí vždy „Zrušit“ → hlavní akce
 * @param {boolean}         [closable] - false během ukládání: dialog nejde zavřít
 */
export default function Modal({ show, title, size, onClose, children, footer, closable = true }) {

    const dialogRef  = useRef(null);
    const restoreRef = useRef(null);

    // onClose/closable čteme přes ref, aby efekt nezávisel na jejich referenci.
    // Jinak se při každém překreslení (třeba psaní do pole) efekt re-runoval a
    // autofocus níže vracel kurzor zpět na první pole — psát do 2. pole nešlo.
    const onCloseRef  = useRef(onClose);
    const closableRef = useRef(closable);
    onCloseRef.current  = onClose;
    closableRef.current = closable;

    useEffect(() => {
        if (!show) return undefined;

        restoreRef.current = document.activeElement;
        document.body.classList.add("modal-open");

        function onKeyDown(e) {
            if (e.key === "Escape" && closableRef.current) {
                /*
                 * Nad otevřeným menu patří Escape jemu. MUI `Menu` se vykresluje portálem do
                 * `body`, tedy MIMO dialog — bez této pojistky doletěl Escape sem a zavřel
                 * rovnou celé okno i s menu (odhaleno u detailu objednávky 2026-08-04, kde je
                 * menu „⋯" ve footeru). Menu si zavření obslouží samo.
                 */
                if (document.querySelector(".MuiPopover-root")) {
                    return;
                }
                onCloseRef.current();
                return;
            }
            if (e.key !== "Tab") return;

            // Focus trap — Tab nesmí utéct z dialogu na pozadí.
            const focusable = dialogRef.current?.querySelectorAll(
                'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
            );
            if (!focusable || focusable.length === 0) return;

            const first = focusable[0];
            const last  = focusable[focusable.length - 1];

            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }

        document.addEventListener("keydown", onKeyDown);

        // Fokus na první pole **v těle** dialogu, ať se dá rovnou psát. Hledat
        // v celém dialogu nestačí: první ovládací prvek v pořadí je křížek
        // v hlavičce, takže by uživatel musel k poli teprve doTabovat.
        const body = dialogRef.current?.querySelector(".modal-body");
        const POLE = 'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])';
        const cil = body?.querySelector(POLE)
            ?? dialogRef.current?.querySelector(POLE)
            ?? dialogRef.current?.querySelector("button:not([disabled])");
        cil?.focus();

        return () => {
            document.removeEventListener("keydown", onKeyDown);
            document.body.classList.remove("modal-open");
            restoreRef.current?.focus?.();
        };
    }, [show]);

    if (!show) return null;

    return createPortal(
        <>
            <div className="modal-backdrop fade show" />
            <div className="modal fade show d-block" role="dialog" aria-modal="true"
                 aria-label={title}
                 onMouseDown={(e) => {
                     // jen klik přímo na plochu mimo dialog, ne protažení výběru textu zevnitř
                     if (closable && e.target === e.currentTarget) onClose();
                 }}>
                <div className={`modal-dialog modal-dialog-centered modal-dialog-scrollable ${size ?? ""}`.trim()}
                     ref={dialogRef}>
                    <div className="modal-content">
                        <div className="modal-header">
                            <h2 className="modal-title fs-5">{title}</h2>
                            {closable && (
                                <button type="button" className="btn-close"
                                        onClick={onClose} aria-label="Zavřít"></button>
                            )}
                        </div>
                        <div className="modal-body">{children}</div>
                        {footer && <div className="modal-footer">{footer}</div>}
                    </div>
                </div>
            </div>
        </>,
        document.body
    );
}
