import React, { useEffect, useState } from "react";
import { api, problemMessage } from "../api/api.js";
import { formatCurrency, getFormDate } from "../api/format.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

/**
 * Dialog vystavení pokladního dokladu — tady doklad dostává číslo a datum vystavení (V92).
 *
 * Zrcadlí InvoiceIssueModal: přepínač „Generovat číslo podle masky" (Fakturační údaje) řídí
 * jen **předvyplnění** návrhem z `GET /cash-receipts/next-number`; v obou režimech jde zapsat
 * libovolné číslo do 20 znaků, unikátnost hlídá server. Návrh se tahá pro zvolené datum
 * a při jeho změně se přenačte — číslo a datum pak vždy pocházejí z téhož období.
 *
 * Navíc proti fakturám: je-li zapnuté hlídání mezer, dialog vypíše chybějící čísla období —
 * díra po smazaném dokladu se zavírá právě ručním zápisem čísla tady.
 *
 * @param {boolean}  show      - zda je dialog vidět
 * @param {Object}   invoice   - hrazená faktura (částka a číslo do kontextu formuláře)
 * @param {Function} onIssue   - async ({ receiptNumber, issueDate }) => void; chybu nechá probublat
 * @param {Function} onCancel  - zavření dialogu
 */
export default function CashReceiptIssueModal({ show, invoice, onIssue, onCancel }) {

    const [receiptNumber, setReceiptNumber] = useState("");
    const [issueDate, setIssueDate] = useState("");
    /** Zdroj čísla z nastavení (V93): 'MASK' | 'INVOICE' | 'MANUAL'; null = ještě nenačteno. */
    const [source, setSource] = useState(null);
    const [gaps, setGaps] = useState(null);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");

    const today = getFormDate();

    // Otevření dialogu: PPD je okamžité potvrzení příjmu — datum je dnešek, návrh čísla pro něj.
    useEffect(() => {
        if (!show) {
            return;
        }
        let cancelled = false;
        setError("");
        setIssueDate(today);
        setLoading(true);
        Promise.all([loadSuggestion(today), loadGaps()])
            .then(([suggestion, gapsRes]) => {
                if (cancelled) return;
                setSource(suggestion.source);
                setReceiptNumber(suggestion.number);
                setGaps(gapsRes);
            })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [show]);

    /**
     * Změna data mění období řady — návrh se přenačte (ručně přepsané číslo záměrně přepíše).
     * V režimu INVOICE číslo na datu nezávisí, přenačítat není co.
     */
    async function handleIssueDateChange(e) {
        const value = e.target.value;
        setIssueDate(value);
        if (!value || source === 'INVOICE') {
            return;
        }
        setLoading(true);
        setReceiptNumber((await loadSuggestion(value)).number);
        setLoading(false);
    }

    /**
     * Předvyplnění čísla dle nastavení (V93): MASK = návrh řady ze serveru,
     * INVOICE = číslo hrazené faktury, MANUAL/chyba = prázdné pole.
     */
    async function loadSuggestion(forDate) {
        try {
            const res = await api.get(`/cash-receipts/next-number?issueDate=${encodeURIComponent(forDate)}`);
            if (res.source === 'INVOICE') {
                return { source: res.source, number: invoice?.invoiceNumber ?? "" };
            }
            return { source: res.source, number: res.receiptNumber ?? "" };
        } catch {
            return { source: null, number: "" };
        }
    }

    /** Díry v řadě — doplňková informace, selhání se nehlásí. */
    async function loadGaps() {
        try {
            return await api.get('/cash-receipts/number-gaps');
        } catch {
            return null;
        }
    }

    async function handleSubmit() {
        setSaving(true);
        setError("");
        try {
            await onIssue({ receiptNumber: receiptNumber.trim(), issueDate });
        } catch (err) {
            setError(problemMessage(err, "Pokladní doklad se nepodařilo vystavit."));
            // Číslo mezitím mohl obsadit někdo jiný — čerstvý návrh pro další pokus.
            const fresh = await loadSuggestion(issueDate);
            if (fresh && fresh !== receiptNumber) {
                setReceiptNumber(fresh);
            }
        } finally {
            setSaving(false);
        }
    }

    if (!show) {
        return null;
    }

    const busy = loading || saving;
    const amount = invoice?.totalToPay ?? invoice?.totalGross;

    return (
        <Modal show={show} title="Vystavení pokladního dokladu" onClose={onCancel} closable={!saving}
               footer={
                   <>
                       <button type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={saving}>
                           Zrušit
                       </button>
                       {/* zelená = nevratný posun dokladu (frontend.md §10.8) */}
                       <button type="button" className="btn btn-success" onClick={handleSubmit}
                               disabled={busy || !receiptNumber.trim() || !issueDate}>
                           {saving ? "Vystavuji…" : "Vystavit doklad"}
                       </button>
                   </>
               }>
            {error && <div className="alert alert-danger py-2">{error}</div>}

            <p className="text-muted small">
                Doklad potvrzuje příjem <strong>{formatCurrency(amount)}</strong> v hotovosti
                k faktuře {invoice?.invoiceNumber}. Pole označená <RequiredMark /> jsou povinná.
            </p>

            <div className="row g-3">
                <div className="col-md-6">
                    <label className="form-label" htmlFor="receipt-number">
                        Číslo dokladu <RequiredMark />
                    </label>
                    <input type="text" id="receipt-number" className="form-control font-monospace"
                           value={receiptNumber} onChange={e => setReceiptNumber(e.target.value)}
                           maxLength={20} disabled={busy} required />
                    {/* Text říká, ODKUD číslo přišlo — prázdné pole jinak vypadá jako chyba načtení. */}
                    <div className="form-text">
                        {loading
                            ? "Načítám návrh…"
                            : (source === 'INVOICE'
                                ? "Číslo hrazené faktury (dle nastavení); lze přepsat."
                                : receiptNumber
                                    ? "Návrh podle číselné řady; lze přepsat."
                                    : "Automatické číslování je vypnuté — zadejte číslo.")}
                    </div>
                </div>
                <div className="col-md-6">
                    <label className="form-label" htmlFor="receipt-issueDate">
                        Datum vystavení <RequiredMark />
                    </label>
                    <input type="date" id="receipt-issueDate" className="form-control"
                           value={issueDate} onChange={handleIssueDateChange}
                           disabled={busy} required />
                    <div className="form-text">
                        {source === 'INVOICE'
                            ? "Den přijetí hotovosti."
                            : "Den přijetí hotovosti. Změna přenačte návrh čísla."}
                    </div>
                </div>
            </div>

            {/* Díry v řadě (V92): po smazaném dokladu je zavírá právě ruční zápis čísla. */}
            {gaps?.enabled && gaps.missingNumbers?.length > 0 && (
                <div className="alert alert-warning py-2 small mt-3 mb-0">
                    V číselné řadě tohoto období chybí:{" "}
                    <span className="font-monospace">{gaps.missingNumbers.join(", ")}</span>.
                    Díru zavřete tak, že chybějící číslo zapíšete do pole výše.
                </div>
            )}
        </Modal>
    );
}
