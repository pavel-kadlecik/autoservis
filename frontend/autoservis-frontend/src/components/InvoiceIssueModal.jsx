import React, { useEffect, useState } from "react";
import { api, problemMessage } from "../api/api.js";
import { getFormDate } from "../api/format.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Dialog vystavení faktury — tady doklad dostává číslo, variabilní symbol a datum vystavení.
 *
 * Číslo koncept schválně nemá: přidělené až při vystavení nemůže zaniknout se zrušeným
 * konceptem, takže řada zůstane souvislá a vzestupná podle data vystavení.
 *
 * Přepínač „Generovat číslo faktury podle masky" (Fakturační údaje) řídí jen **předvyplnění**:
 * zapnutý → návrh dalšího čísla řady (`GET /invoices/next-number`), vypnutý → prázdné pole.
 * V obou režimech jde zapsat libovolné číslo; hlídá se unikátnost a délka do 20 znaků.
 * VS se odvodí z číslic navrženého čísla (běžná praxe „VS = číslo faktury"); nevejde-li se
 * do 10 číslic, zůstane prázdný a vyplní ho obsluha.
 *
 * **Datum vystavení** se předvyplní datem z konceptu a obsluha ho tu může upravit — doklad
 * odchází s ním, server ho nepřepisuje (rozhodnutí uživatele 2026-08-07). Návrh čísla se
 * proto tahá *pro zvolené datum* (`?issueDate=`) a při jeho změně se přenačte: číslo a datum
 * pak vždycky pocházejí z téhož období. Budoucí datum server odmítne, proto `max` = dnešek.
 *
 * @param {boolean}  show      - zda je dialog vidět
 * @param {Object}   invoice   - vystavovaný koncept (datum vystavení + kontext pod formulářem);
 *                               snese řádek seznamu i detail dokladu
 * @param {Function} onIssue   - async ({ invoiceNumber, issueDate, variableSymbol }) => void; chybu nechá probublat
 * @param {Function} onCancel  - zavření dialogu
 */
export default function InvoiceIssueModal({ show, invoice, onIssue, onCancel }) {

    const [invoiceNumber, setInvoiceNumber] = useState("");
    const [issueDate, setIssueDate] = useState("");
    const [variableSymbol, setVariableSymbol] = useState("");
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");

    const today = getFormDate();

    // Otevření dialogu: datum převezmi z konceptu (fallback dnešek, kdyby ho doklad nenesl)
    // a natáhni návrh čísla pro něj.
    useEffect(() => {
        if (!show) {
            return;
        }
        let cancelled = false;
        const initialDate = invoice?.issueDate ?? today;
        setError("");
        setIssueDate(initialDate);
        setLoading(true);
        loadSuggestion(initialDate)
            .then(number => {
                if (cancelled) return;
                setInvoiceNumber(number);
                setVariableSymbol(toVariableSymbol(number));
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [show]);

    /**
     * Změna data mění i období řady, ze kterého se skládá číslo — návrh se proto přenačte.
     * Ručně přepsané číslo se tím přepíše záměrně: nechat u srpnového data zářijové číslo
     * by byla přesně ta rozejitá dvojice, které se celý dialog vyhýbá.
     */
    async function handleIssueDateChange(e) {
        const value = e.target.value;
        setIssueDate(value);
        if (!value) {
            return;
        }
        setLoading(true);
        const number = await loadSuggestion(value);
        setInvoiceNumber(number);
        setVariableSymbol(toVariableSymbol(number));
        setLoading(false);
    }

    /** Návrh dalšího čísla řady pro dané datum; při vypnutém automatu nebo chybě vrací "" (ruční zápis). */
    async function loadSuggestion(forDate) {
        try {
            const res = await api.get(`/invoices/next-number?issueDate=${encodeURIComponent(forDate)}`);
            return res.auto ? (res.invoiceNumber ?? "") : "";
        } catch {
            return "";
        }
    }

    async function handleSubmit() {
        setSaving(true);
        setError("");
        try {
            await onIssue({
                invoiceNumber: invoiceNumber.trim(),
                issueDate,
                variableSymbol: variableSymbol.trim() || null,
            });
        } catch (err) {
            setError(problemMessage(err, "Fakturu se nepodařilo vystavit."));
            // Číslo mezitím mohl obsadit někdo jiný — natáhni čerstvý návrh, ať další
            // pokus nekončí stejnou chybou.
            const fresh = await loadSuggestion(issueDate);
            if (fresh && fresh !== invoiceNumber) {
                setInvoiceNumber(fresh);
                setVariableSymbol(toVariableSymbol(fresh));
            }
        } finally {
            setSaving(false);
        }
    }

    if (!show) {
        return null;
    }

    const busy = loading || saving;
    // Dialog se otevírá ze seznamu i z detailu a ty nesou týž údaj pod jiným jménem:
    // ListResponse má orderNumber/customerDisplayName, DetailResponse snapshoty dokladu.
    const orderNumber = invoice?.orderNumberSnapshot ?? invoice?.orderNumber;
    const customerName = invoice?.customerNameSnapshot ?? invoice?.customerDisplayName;

    return (
        <Modal show={show} title="Vystavení faktury" onClose={onCancel} closable={!saving}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       {/* zelená = nevratný posun dokladu (frontend.md §10.8) */}
                       <button type="button" className="btn btn-success" onClick={handleSubmit}
                               disabled={busy || !invoiceNumber.trim() || !issueDate}>
                           {saving ? "Vystavuji…" : "Vystavit"}
                       </button>
                   </>
               }>
            {error && <div className="alert alert-danger py-2">{error}</div>}

            <p className="text-muted small">
                Vystavením dostane doklad číslo a stává se neměnným. Opravit ho pak lze
                jen opravným daňovým dokladem (dobropisem). Pole označená <RequiredMark /> jsou povinná.
            </p>

            <div className="row g-3">
                <div className="col-md-6">
                    <label className="form-label" htmlFor="issue-invoiceNumber">
                        Číslo faktury <RequiredMark />
                    </label>
                    <input type="text" id="issue-invoiceNumber" className="form-control font-monospace"
                           value={invoiceNumber} onChange={e => setInvoiceNumber(e.target.value)}
                           maxLength={20} disabled={busy} required />
                    {/* Prázdné pole = vypnutý automat ve Fakturačních údajích. Bez téhle věty
                        vypadá prázdný formulář jako chyba načtení, ne jako nastavený režim. */}
                    <div className="form-text">
                        {loading
                            ? "Načítám návrh…"
                            : (invoiceNumber
                                ? "Návrh podle číselné řady; lze přepsat."
                                : "Automatické číslování je vypnuté — zadejte číslo.")}
                    </div>
                </div>
                <div className="col-md-6">
                    <label className="form-label" htmlFor="issue-issueDate">
                        Datum vystavení <RequiredMark />
                    </label>
                    <input type="date" id="issue-issueDate" className="form-control"
                           value={issueDate} onChange={handleIssueDateChange}
                           disabled={busy} required />
                    <div className="form-text">
                        Datum z konceptu; s tímhle datem doklad odejde. Změna přenačte návrh čísla.
                    </div>
                </div>
            </div>

            <div className="row g-3 mt-1">
                <div className="col-md-6">
                    <label className="form-label" htmlFor="issue-variableSymbol">Variabilní symbol</label>
                    <input type="text" id="issue-variableSymbol" className="form-control"
                           value={variableSymbol} onChange={e => setVariableSymbol(e.target.value)}
                           maxLength={10} inputMode="numeric" pattern="[0-9]{1,10}" disabled={busy} />
                    <div className="form-text">Jen číslice, max. 10. Nepovinné.</div>
                </div>
            </div>

            {orderNumber && (
                <div className="text-muted small mt-3">
                    Zakázka {orderNumber}{customerName ? ` · ${customerName}` : ""}
                </div>
            )}
        </Modal>
    );
}

/** VS z čísla faktury: jen číslice; delší než 10 (limit sloupce) se nedosazuje. */
function toVariableSymbol(invoiceNumber) {
    const digits = (invoiceNumber ?? "").replace(/\D/g, "");
    return digits.length > 0 && digits.length <= 10 ? digits : "";
}
