import * as React from "react";
import Modal from "./Modal.jsx";
import { invoiceBlock } from "./invoiceBlock.js";

/**
 * Potvrzení tvrdého smazání zakázky — sdílené seznamem, detailem i editací.
 *
 * <p>Protějšek {@link OrderCancelDialog}, se stejnými třemi větvemi podle faktury. Rovnalo se
 * to 2026-08-07: mazání mělo dosud jen prosté ano/ne, takže obsluha s konceptem faktury dostala
 * suché 422, musela odejít do faktur, koncept smazat a vrátit se — zatímco dialog zrušení jí
 * totéž nabídl jedním tlačítkem.
 *
 * <ul>
 *   <li><strong>bez faktury</strong> — potvrzení a jde se na to,</li>
 *   <li><strong>koncept</strong> — nabídne se rovnou jeho storno; koncept nikam neodešel
 *       a číslo nemá, takže v číselné řadě nevznikne mezera,</li>
 *   <li><strong>vystavená / zaplacená</strong> — smazat <em>nepůjde nikdy</em>, ani po
 *       dobropisu. Dialog proto nic nepotvrzuje a nabídne zrušení místo mazání.</li>
 * </ul>
 *
 * <p><strong>Proč u vystavené faktury „nikdy":</strong> zrušení se ptá jen na <em>aktivní</em>
 * fakturu, takže dobropis zakázku uvolní. Mazání se ptá na <em>jakoukoli</em> — rozhoduje,
 * jestli po zakázce kdy zůstala stopa v účetnictví, a dobropisovaná faktura řádek v evidenci
 * nechává. Frontend proto u vystavené faktury nenabízí „vystavte dobropis a zkuste znovu";
 * byla by to slepá ulička.
 *
 * <p>Skladu se dialog neptá vůbec — materiál mazání od V87 nebrání, vrátí se sám.
 *
 * @param {object|null} order          zakázka ke smazání; null = dialog je zavřený
 * @param {Function}    onConfirm      smaže zakázku (koncept už je stornovaný)
 * @param {Function}    onCancel
 * @param {Function}    onDeleteDraft  smaže koncept faktury; vrací Promise
 * @param {Function}    onSwitchToCancel  přepne na dialog zrušení
 */
export default function OrderDeleteDialog({ order, onConfirm, onCancel, onDeleteDraft, onSwitchToCancel }) {

    const [busy, setBusy] = React.useState(false);

    if (!order) return null;

    const hasDraft = invoiceBlock(order) === "draft";
    const hasIssued = invoiceBlock(order) === "issued";

    /* Vyfakturovanou zakázku smazat nelze a nepomůže ani dobropis — jediná cesta je zrušení. */
    if (hasIssued) {
        return (
            <Modal
                show={true}
                title="Zakázku nelze smazat"
                onClose={onCancel}
                footer={
                    <>
                        <button type="button" className="btn btn-outline-secondary" onClick={onCancel}>
                            Zpět
                        </button>
                        <button type="button" className="btn btn-primary"
                                onClick={() => onSwitchToCancel(order)}>
                            Zrušit zakázku místo mazání
                        </button>
                    </>
                }
            >
                <p>
                    Zakázka {order.orderNumber} byla <strong>fakturovaná</strong>, takže po ní
                    zůstala stopa v účetnictví. Smazat ji nepůjde ani po dobropisu — doklad
                    v evidenci zůstává.
                </p>
                <p className="mb-0 text-body-secondary">
                    Pokud k opravě nedošlo, <strong>zrušte ji</strong> místo mazání. Zůstane
                    v evidenci, což je u fakturované zakázky správně.
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
            title="Opravdu smazat zakázku?"
            onClose={onCancel}
            footer={
                <>
                    <button type="button" className="btn btn-outline-secondary"
                            onClick={onCancel} disabled={busy}>
                        Zpět
                    </button>
                    <button type="button" className="btn btn-danger"
                            onClick={handleConfirm} disabled={busy}>
                        {hasDraft ? "Stornovat koncept a smazat" : "Smazat zakázku"}
                    </button>
                </>
            }
        >
            <p>
                Zakázka {order.orderNumber} zmizí i s položkami a se zápisem tachometru, který
                se při jejím založení přidal do historie vozidla. <strong>Vzít zpět to nejde.</strong>
            </p>
            <p>
                Materiál, který z ní odešel ze skladu, se přitom <strong>vrátí zpět</strong>.
            </p>
            {hasDraft && (
                <p>
                    Zakázka má <strong>koncept faktury</strong> — smaže se spolu s ní. Koncept
                    nikam neodešel a číslo nemá, takže v číselné řadě nevznikne mezera.
                </p>
            )}
            <p className="mb-0 text-body-secondary">
                Mazání je pro zakázku založenou omylem — pokud byla skutečná a jen k opravě
                nedošlo, zrušte ji místo mazání, ať zůstane v evidenci.
            </p>
        </Modal>
    );
}
