import * as React from "react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import VisibilityIcon from "@mui/icons-material/Visibility";
import SendIcon from "@mui/icons-material/Send";
import PaidIcon from "@mui/icons-material/Paid";
import HandshakeIcon from "@mui/icons-material/Handshake";
import ForwardToInboxIcon from "@mui/icons-material/ForwardToInbox";
import UndoIcon from "@mui/icons-material/Undo";
import DeleteIcon from "@mui/icons-material/Delete";

import { api, problemMessage } from "../api/api.js";
import { useAlert } from "../context/AlertContext.jsx";
import ConfirmDialog from "./ConfirmDialog.jsx";
import InvoiceIssueModal from "./InvoiceIssueModal.jsx";
import InvoiceSendEmailModal from "./InvoiceSendEmailModal.jsx";

/**
 * Přechody mezi stavy faktury pro řádkové menu.
 *
 * <p>Nabízí se <strong>jen pohyb po ose</strong> — krok vpřed a krok zpět z aktuálního stavu
 * (rozhodnutí uživatele 2026-08-08), k tomu navigace na detail a mazání:
 *
 * <pre>
 *   Koncept                  ↑ Vystavit                                           · Smazat
 *   Vystavena (nepředaná)    ↑ Předat · Označit zaplaceno   ↓ Vrátit do konceptu   · Smazat
 *   Předána                  ↑ Označit zaplaceno            ↓ Vzít předání zpět
 *   Zaplacena                                               ↓ Vzít platbu zpět
 *   Dobropisována            — terminál (u zaplacené zbývá ↓ Vzít platbu zpět)
 * </pre>
 *
 * <p>PDF, pokladní ani opravný doklad sem <strong>nepatří</strong> — nejsou to přechody stavu
 * a menu by přestalo být přehledné. Výjimka: „Poslat e-mailem" u předané faktury (2026-08-08) —
 * opakované odeslání dokladu, který zákazník ztratil; první odeslání kryje dialog předání.
 *
 * <p>Skok z <em>Vystavena</em> rovnou na <em>Zaplaceno</em> se nabízí schválně: zákazník platí
 * na místě dřív, než mu doklad podáš, a „Označit zaplaceno" předání orazítkuje samo.
 *
 * <p><em>Smazat</em> se nabízí jen tam, kde projde (koncept a vystavená nepředaná, V88) —
 * jinde by položka svítila a backend by ji odmítl.
 */
export function invoiceActionItems(invoice, context) {
    if (!invoice) return [];

    const draft    = invoice.status === 'DRAFT';
    const issued   = invoice.status === 'ISSUED';
    const paid     = invoice.status === 'PAID';
    const handed   = Boolean(invoice.handedOverAt);
    const credited = Boolean(invoice.creditedAt);

    return [
        context === 'list' && {id: "detail", label: "Detail", icon: <VisibilityIcon fontSize="small"/>},

        // ── krok vpřed ───────────────────────────────────────────────────
        draft && {id: "issue", label: "Vystavit", icon: <SendIcon fontSize="small"/>},
        issued && !handed && !credited
            && {id: "hand-over", label: "Předat zákazníkovi", icon: <HandshakeIcon fontSize="small"/>},
        issued && !credited
            && {id: "pay", label: "Označit zaplaceno", icon: <PaidIcon fontSize="small"/>},

        // Opakované odeslání předaného dokladu (zákazník fakturu ztratil, překlep v adrese).
        // První odeslání nabízí dialog předání, proto se tu nepředaná faktura neukazuje.
        (issued || paid) && handed
            && {id: "send-email", label: "Poslat e-mailem", icon: <ForwardToInboxIcon fontSize="small"/>},

        // ── krok zpět (oprava překlepu) ──────────────────────────────────
        issued && !handed
            && {id: "revoke-issue", label: "Vrátit do konceptu", icon: <UndoIcon fontSize="small"/>,
                title: "Uvolní číslo i variabilní symbol; položky a strany zůstanou"},
        issued && handed && !credited
            && {id: "revoke-hand-over", label: "Vzít předání zpět", icon: <UndoIcon fontSize="small"/>},
        paid && {id: "revoke-payment", label: "Vzít platbu zpět", icon: <UndoIcon fontSize="small"/>},

        (draft || (issued && !handed))
            && {id: "delete", label: "Smazat", icon: <DeleteIcon fontSize="small"/>, tone: "danger"},
    ].filter(Boolean);
}

/**
 * Chování přechodů — volání serveru, hlášky a dva dialogy, které přechod potřebuje
 * (vystavení kvůli číslu a VS, mazání kvůli potvrzení). Ostatní jdou rovnou.
 *
 * @param {{onChanged?: Function, onDeleted?: Function}} [options]
 * @returns {{run: Function, dialogs: React.ReactNode, busy: boolean}}
 */
export function useInvoiceActions({ onChanged, onDeleted } = {}) {

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [busy, setBusy] = useState(false);
    const [toIssue, setToIssue] = useState(null);
    const [toDelete, setToDelete] = useState(null);
    /** Dialog e-mailu: { invoice, mode: 'hand-over' | 'send' }, nebo null. */
    const [emailDialog, setEmailDialog] = useState(null);

    async function call(request, successMessage, fallback) {
        setBusy(true);
        try {
            const updated = await request();
            addAlert(successMessage, "success");
            onChanged?.(updated);
        } catch (err) {
            addAlert(problemMessage(err, fallback), "danger");
        } finally {
            setBusy(false);
        }
    }

    function run(actionId, invoice) {
        switch (actionId) {
            case "detail":
                return navigate(`/invoices/${invoice.id}/detail`);
            case "issue":
                return setToIssue(invoice);
            case "delete":
                return setToDelete(invoice);
            // Předání otevírá dialog s nabídkou poslat fakturu e-mailem (2026-08-08) —
            // samotné označení předání v něm zůstává („Předat bez e-mailu").
            case "hand-over":
                return setEmailDialog({ invoice, mode: 'hand-over' });
            case "send-email":
                return setEmailDialog({ invoice, mode: 'send' });
            case "revoke-hand-over":
                return call(() => api.delete(`/invoices/${invoice.id}/hand-over`),
                    "Předání vzato zpět.", "Předání se nepodařilo vzít zpět.");
            case "pay":
                return call(() => api.post(`/invoices/${invoice.id}/pay`, {}),
                    "Faktura označena jako zaplacená.", "Stav faktury se nepodařilo změnit.");
            case "revoke-payment":
                return call(() => api.delete(`/invoices/${invoice.id}/pay`),
                    "Platba vzata zpět — faktura je zase nezaplacená.",
                    "Platbu se nepodařilo vzít zpět.");
            case "revoke-issue":
                return call(() => api.delete(`/invoices/${invoice.id}/issue`),
                    "Faktura je zpět v konceptu — číslo se uvolnilo.",
                    "Fakturu se nepodařilo vrátit do konceptu.");
            default:
                return undefined;
        }
    }

    async function deleteInvoice() {
        const invoice = toDelete;
        setToDelete(null);
        setBusy(true);
        try {
            await api.delete(`/invoices/${invoice.id}`);
            addAlert("Faktura byla smazána.", "success");
            onDeleted?.(invoice);
        } catch (err) {
            addAlert(problemMessage(err, "Fakturu se nepodařilo smazat."), "danger");
        } finally {
            setBusy(false);
        }
    }

    const dialogs = (
        <>
            {/* Předání s nabídkou e-mailu i samostatné (opakované) odeslání — týž dialog,
                režim rozlišuje jen titulek a tlačítka. */}
            <InvoiceSendEmailModal
                show={emailDialog !== null}
                invoice={emailDialog?.invoice}
                mode={emailDialog?.mode}
                onDone={(updated, { sent }) => {
                    setEmailDialog(null);
                    addAlert(sent
                        ? `Faktura ${updated.invoiceNumber} odeslána e-mailem.`
                        : "Faktura označena jako předaná zákazníkovi.", "success");
                    onChanged?.(updated);
                }}
                onCancel={() => setEmailDialog(null)}
            />

            {/* Vystavení potřebuje formulář — doklad tu dostává číslo, datum a VS. */}
            <InvoiceIssueModal
                show={toIssue !== null}
                invoice={toIssue}
                onIssue={async request => {
                    const updated = await api.post(`/invoices/${toIssue.id}/issue`, request);
                    setToIssue(null);
                    addAlert(`Faktura ${updated.invoiceNumber} vystavena.`, "success");
                    onChanged?.(updated);
                }}
                onCancel={() => setToIssue(null)}
            />

            <ConfirmDialog
                title={toDelete?.status === 'DRAFT'
                    ? "Smazat koncept faktury?" : "Smazat vystavenou fakturu?"}
                message={toDelete && (toDelete.status === 'DRAFT'
                    ? "Koncept se smaže i s položkami a nelze ho vrátit. Zakázku pak můžete "
                      + "znovu upravit a vyfakturovat."
                    : `Faktura ${toDelete.invoiceNumber} se smaže i s položkami a nelze ji vrátit. `
                      + "Jde to jen proto, že jste ji neoznačili jako předanou zákazníkovi — pokud "
                      + "ji přesto někdo dostal, smazat ji nesmíte a musíte vystavit dobropis. "
                      + "Číslo se uvolní; příští fakturu můžete vystavit s ním.")}
                show={toDelete !== null}
                onConfirm={deleteInvoice}
                onCancel={() => setToDelete(null)}
            />
        </>
    );

    return { run, dialogs, busy };
}
