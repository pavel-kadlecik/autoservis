import React, { useEffect, useState } from "react";
import { api, problemMessage } from "../api/api.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Dialog odeslání faktury e-mailem (2026-08-08) — PDF dokladu jako příloha.
 *
 * Kostru e-mailu (adresát, předmět, text) skládá server (`GET /invoices/{id}/email-draft`)
 * z dat dokladu a karty zákazníka; obsluha ji tu libovolně upraví a odešle se **přesně
 * potvrzené znění** (`POST /invoices/{id}/send-email`). Úspěšné odeslání razítkuje předání —
 * e-mail ve schránce = doklad u zákazníka (V88).
 *
 * Dva režimy podle místa otevření:
 *  - `hand-over` — akce „Předat zákazníkovi" u nepředané faktury. Nabízí obě cesty:
 *    „Předat bez e-mailu" (dnešní chování) a „Předat a poslat e-mail". Dvě tlačítka místo
 *    checkboxu — obsluha volí až činem a nemůže odeslat omylem předvoleným zaškrtnutím.
 *  - `send` — samostatná akce „Poslat e-mailem" (typicky opakované odeslání už předané
 *    faktury); předání se nemění, tlačítko je jen jedno.
 *
 * @param {boolean}  show      zda je dialog vidět
 * @param {Object}   invoice   faktura (snese řádek seznamu i detail — potřebuje jen id a číslo)
 * @param {'hand-over'|'send'} mode režim (viz výše)
 * @param {Function} onDone    async (updatedInvoice, {sent: boolean}) => void po úspěchu
 * @param {Function} onCancel  zavření dialogu
 */
export default function InvoiceSendEmailModal({ show, invoice, mode, onDone, onCancel }) {

    const [recipient, setRecipient] = useState("");
    const [subject, setSubject] = useState("");
    const [body, setBody] = useState("");
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");

    // Otevření dialogu: natáhni návrh e-mailu. Selhání návrhu dialog nezavírá —
    // obsluha může pole vyplnit ručně, nebo (v režimu předání) předat bez e-mailu.
    useEffect(() => {
        if (!show) {
            return;
        }
        let cancelled = false;
        setError("");
        setLoading(true);
        api.get(`/invoices/${invoice.id}/email-draft`)
            .then(draft => {
                if (cancelled) return;
                setRecipient(draft.recipient ?? "");
                setSubject(draft.subject ?? "");
                setBody(draft.body ?? "");
            })
            .catch(err => {
                if (cancelled) return;
                setError(problemMessage(err, "Návrh e-mailu se nepodařilo načíst."));
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [show]);

    async function sendEmail() {
        setSaving(true);
        setError("");
        try {
            const updated = await api.post(`/invoices/${invoice.id}/send-email`, {
                recipient: recipient.trim(),
                subject: subject.trim(),
                body,
            });
            await onDone?.(updated, { sent: true });
        } catch (err) {
            // Chyba zůstává v dialogu (typicky překlep v adrese nebo nedostupný SMTP) —
            // obsluha ji opraví tady, ne v novém otevření.
            setError(problemMessage(err, "E-mail se nepodařilo odeslat."));
        } finally {
            setSaving(false);
        }
    }

    async function handOverOnly() {
        setSaving(true);
        setError("");
        try {
            const updated = await api.post(`/invoices/${invoice.id}/hand-over`, {});
            await onDone?.(updated, { sent: false });
        } catch (err) {
            setError(problemMessage(err, "Předání se nepodařilo označit."));
        } finally {
            setSaving(false);
        }
    }

    if (!show) {
        return null;
    }

    const busy = loading || saving;
    const canSend = !busy && recipient.trim() && subject.trim() && body.trim();
    const handOverMode = mode === 'hand-over';

    return (
        <Modal show={show}
               title={handOverMode ? "Předat fakturu zákazníkovi" : "Poslat fakturu e-mailem"}
               onClose={onCancel} closable={!saving}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary"
                               onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       {handOverMode && (
                           <button type="button" className="btn btn-outline-primary"
                                   onClick={handOverOnly} disabled={saving}>
                               Předat bez e-mailu
                           </button>
                       )}
                       {/* zelená = nevratný posun dokladu (frontend.md §10.8): odeslaný
                           e-mail nejde vzít zpět a razítkuje předání */}
                       <button type="button" className="btn btn-success"
                               onClick={sendEmail} disabled={!canSend}>
                           {saving ? "Odesílám…"
                               : (handOverMode ? "Předat a poslat e-mail" : "Poslat e-mail")}
                       </button>
                   </>
               }>
            {error && <div className="alert alert-danger py-2">{error}</div>}

            <p className="text-muted small">
                {handOverMode
                    ? <>Faktura se označí jako předaná zákazníkovi. Můžete ji rovnou poslat
                        e-mailem — PDF dokladu odejde jako příloha.</>
                    : <>Faktura {invoice.invoiceNumber} odejde jako PDF v příloze tohoto
                        e-mailu.</>}
                {" "}Pole označená <RequiredMark /> jsou povinná pro odeslání.
            </p>

            <div className="mb-3">
                <label className="form-label" htmlFor="email-recipient">
                    Komu <RequiredMark />
                </label>
                <input type="email" id="email-recipient" className="form-control"
                       value={recipient} onChange={e => setRecipient(e.target.value)}
                       maxLength={255} disabled={busy} required />
                <div className="form-text">
                    {loading
                        ? "Načítám návrh…"
                        : (recipient
                            ? "E-mail z karty zákazníka; lze přepsat."
                            : "Zákazník nemá na kartě e-mail — zadejte adresu ručně.")}
                </div>
            </div>

            <div className="mb-3">
                <label className="form-label" htmlFor="email-subject">
                    Předmět <RequiredMark />
                </label>
                <input type="text" id="email-subject" className="form-control"
                       value={subject} onChange={e => setSubject(e.target.value)}
                       maxLength={200} disabled={busy} required />
            </div>

            <div className="mb-1">
                <label className="form-label" htmlFor="email-body">
                    Text e-mailu <RequiredMark />
                </label>
                <textarea id="email-body" className="form-control" rows={8}
                          value={body} onChange={e => setBody(e.target.value)}
                          maxLength={5000} disabled={busy} required />
                <div className="form-text">
                    Připravenou kostru můžete libovolně doplnit nebo přepsat — odešle se
                    přesně tento text.
                </div>
            </div>
        </Modal>
    );
}
