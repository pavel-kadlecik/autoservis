import * as React from "react";
import { useNavigate } from "react-router-dom";
import Modal from "./Modal.jsx";
import { invoiceBlock } from "./invoiceBlock.js";

/**
 * Potvrzení zrušení zakázky — sdílené seznamem i detailem.
 *
 * Zrušení sahá do skladu (vrací vydaný materiál), takže má vlastní dialog místo prostého
 * ano/ne u ostatních přechodů. Obsah se řídí **aktivní fakturou zakázky**, protože ta je
 * jediná věc, která zrušení skutečně zablokuje:
 *
 * - **bez faktury** — potvrzení a jde se na to,
 * - **koncept** — nabídne se rovnou storno konceptu; koncept nikam neodešel a čísla nemá,
 *   takže jeho smazání nedělá do řady mezeru. Bez toho by obsluha odešla na jinou obrazovku,
 *   koncept smazala a musela se pro zrušení vrátit,
 * - **vystavená / zaplacená** — zrušit nelze a nikdy nepůjde, dokud doklad platí. Storno je
 *   od KN-1 zakázané (§42/§45 ZDPH), opravuje se **dobropisem**. Dialog proto nepotvrzuje,
 *   ale posílá na fakturu, odkud se dobropis vystavuje.
 *
 * @param {object|null} order      zakázka ke zrušení; null = dialog je zavřený
 * @param {Function}    onConfirm  zavolá se, až je zrušení na řadě (koncept už je smazaný)
 * @param {Function}    onCancel
 * @param {Function}    onDeleteDraft  smaže koncept faktury; vrací Promise
 */
export default function OrderCancelDialog({ order, onConfirm, onCancel, onDeleteDraft }) {

    const navigate = useNavigate();
    const [busy, setBusy] = React.useState(false);

    if (!order) return null;

    const hasDraft = invoiceBlock(order) === "draft";
    const hasIssued = invoiceBlock(order) === "issued";

    /* Vystavený doklad — jediný případ, kdy dialog nic nepotvrzuje, jen ukazuje cestu ven. */
    if (hasIssued) {
        return (
            <Modal
                show={true}
                title="Zakázku nelze zrušit"
                onClose={onCancel}
                footer={
                    <>
                        <button type="button" className="btn btn-outline-secondary" onClick={onCancel}>
                            Zpět
                        </button>
                        <button type="button" className="btn btn-primary"
                                onClick={() => navigate(`/invoices/${order.invoiceId}/detail`)}>
                            Přejít na fakturu
                        </button>
                    </>
                }
            >
                <p>
                    Zakázka {order.orderNumber} má <strong>vystavenou fakturu</strong>. Vedle sebe
                    by stála zrušená práce a platný daňový doklad na ni.
                </p>
                <p className="mb-0 text-body-secondary">
                    Vystavenou fakturu nelze stornovat — vystavte k ní <strong>opravný daňový
                    doklad (dobropis)</strong>. Tím se zakázka uvolní a půjde zrušit.
                </p>
            </Modal>
        );
    }

    async function handleConfirm() {
        setBusy(true);
        try {
            if (hasDraft) await onDeleteDraft(order);
            await onConfirm(order);
        } finally {
            setBusy(false);
        }
    }

    return (
        <Modal
            show={true}
            title="Opravdu zrušit zakázku?"
            onClose={onCancel}
            footer={
                <>
                    <button type="button" className="btn btn-outline-secondary"
                            onClick={onCancel} disabled={busy}>
                        Zpět
                    </button>
                    <button type="button" className="btn btn-primary"
                            onClick={handleConfirm} disabled={busy}>
                        {hasDraft ? "Stornovat koncept a zrušit" : "Zrušit zakázku"}
                    </button>
                </>
            }
        >
            <p>
                Zakázka {order.orderNumber} zůstane v evidenci jako zrušená
                a <strong>všechen materiál, který z ní odešel ze skladu, se vrátí zpět</strong>.
            </p>
            {hasDraft && (
                <p>
                    Zakázka má <strong>koncept faktury</strong> — smaže se spolu se zrušením.
                    Koncept nikam neodešel a číslo nemá, takže v číselné řadě nevznikne mezera.
                </p>
            )}
            <p className="mb-0 text-body-secondary">
                Pokud část dílů zůstala namontovaná na voze, založte na ně novou zakázku —
                zákazník je zaplatí.
            </p>
        </Modal>
    );
}
