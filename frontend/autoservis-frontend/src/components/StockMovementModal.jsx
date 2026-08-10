import * as React from "react";
import { useEffect, useMemo, useState } from "react";
import { api, problemMessage } from "../api/api.js";
import { RETURN_REASON_OPTIONS, formatCurrency, formatQuantity } from "../api/format.js";
import Modal from "./Modal.jsx";

/**
 * Modal pro ruční skladový pohyb (E2.1/P-1) proti konkrétní šarži.
 * Množství se zadává kladné — server ho znegatuje.
 *
 * Nabízí čtyři typy, všechny zásobu snižují a liší se jen důvodem: korekce dolů
 * (ADJUSTMENT), odpis (WRITE_OFF), vratka dodavateli (RETURN — vyžaduje returnReason)
 * a spotřeba bez zakázky (ISSUE). Server je vynucuje v
 * `StockMovementDto.CreateRequest.isManualMovementType`.
 *
 * Přebytek (kladná korekce) se sem záměrně nevejde: naskladňuje se ruční
 * příjemkou, aby vzniklá zásoba měla šarži i cenu (rozhodnutí R-E).
 *
 * @param {boolean}  show
 * @param {Object}   product          - detail produktu (kvůli šaržím a jednotce)
 * @param {Function} onClose()
 * @param {Function} onSaved(product) - aktualizovaný detail z odpovědi serveru
 */
export default function StockMovementModal({ show, product, onClose, onSaved }) {

    const [movementType, setMovementType] = useState("ADJUSTMENT");
    const [batchId, setBatchId] = useState("");
    const [quantity, setQuantity] = useState("");
    const [note, setNote] = useState("");
    const [returnReason, setReturnReason] = useState("");
    const [creditNoteNumber, setCreditNoteNumber] = useState("");
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    const isReturn = movementType === "RETURN";

    // FIFO: nejstarší šarže první (R-A/Z-2). Karta dílu ukazuje šarže nejnovější
    // první — to je historický přehled, tady jde o pořadí odebírání.
    const availableBatches = useMemo(() => (product?.batches ?? [])
            .filter((b) => Number(b.quantityRemaining) > 0)
            .slice()
            .sort((a, b) => {
                if (!a.issueDate) return 1;
                if (!b.issueDate) return -1;
                return a.issueDate.localeCompare(b.issueDate);
            }),
        [product]);

    // Otevření předvybere nejstarší šarži — uživatel ji smí přepsat.
    useEffect(() => {
        if (show && !batchId && availableBatches.length > 0) {
            setBatchId(String(availableBatches[0].batchId));
        }
    }, [show, batchId, availableBatches]);

    function handleClose() {
        setMovementType("ADJUSTMENT");
        setBatchId("");
        setQuantity("");
        setNote("");
        setReturnReason("");
        setCreditNoteNumber("");
        setError("");
        onClose();
    }

    /** Přepnutí typu vyprázdní pole vratky — jinde je server odmítne (400). */
    function handleTypeChange(value) {
        setMovementType(value);
        if (value !== "RETURN") {
            setReturnReason("");
            setCreditNoteNumber("");
        }
    }

    async function handleSubmit() {
        setError("");
        if (!batchId) {
            setError("Vyberte šarži, ze které se pohyb odepíše.");
            return;
        }
        if (!(Number(quantity) > 0)) {
            setError("Množství musí být kladné číslo.");
            return;
        }
        if (note.trim().length < 3) {
            setError("Poznámka je povinná (alespoň 3 znaky) — pohyb musí být zdůvodněn.");
            return;
        }
        if (isReturn && !returnReason) {
            setError("U vratky vyberte důvod.");
            return;
        }
        setSaving(true);
        try {
            const updated = await api.post(`/warehouse/products/${product.id}/movements`, {
                movementType,
                batchId: Number(batchId),
                quantity: Number(quantity),
                note: note.trim(),
                // pole vratky posílá jen vratka — server jinde odmítne (zrcadlí DB CHECK)
                returnReason: isReturn ? returnReason : null,
                creditNoteNumber: isReturn && creditNoteNumber.trim()
                    ? creditNoteNumber.trim() : null,
            });
            onSaved(updated);
            handleClose();
        } catch (err) {
            // 422 nese srozumitelný detail, 400 z Bean Validation nese errors[] — obojí složí
            // sdílený problemMessage (tady vznikl vzor, od KN-14 ho používá celý frontend).
            setError(problemMessage(err, "Pohyb se nepodařilo uložit."));
        } finally {
            setSaving(false);
        }
    }

    return (
        <Modal show={show} title="Skladový pohyb" onClose={handleClose} closable={!saving}
               footer={
                   <>
                       <button type="button" onClick={handleClose} className="btn btn-outline-secondary">Zrušit</button>
                       <button type="button" onClick={handleSubmit} className="btn btn-primary"
                               disabled={saving || availableBatches.length === 0}>
                           {saving ? "Ukládám…" : "Uložit pohyb"}
                       </button>
                   </>
               }>
                        {error && <div className="alert alert-danger py-2">{error}</div>}

                        {availableBatches.length === 0 ? (
                            <p className="text-muted mb-0">
                                Produkt nemá žádnou šarži se zbytkem — není z čeho odepsat.
                            </p>
                        ) : (
                            <>
                                <div className="mb-3">
                                    <label className="form-label" htmlFor="movementType">Typ pohybu</label>
                                    <select id="movementType" className="form-select"
                                            value={movementType}
                                            onChange={(e) => handleTypeChange(e.target.value)}>
                                        <option value="ADJUSTMENT">Korekce − (manko, přepočet)</option>
                                        <option value="WRITE_OFF">Odpis (rozbité, znehodnocené)</option>
                                        <option value="RETURN">Vratka dodavateli (reklamace, špatný díl)</option>
                                        <option value="ISSUE">Spotřeba bez zakázky (dílna, režie)</option>
                                    </select>
                                </div>

                                {isReturn && (
                                    <div className="row g-2 mb-3">
                                        <div className="col-md-6">
                                            <label className="form-label" htmlFor="returnReason">
                                                Důvod vratky
                                            </label>
                                            <select id="returnReason" className="form-select"
                                                    value={returnReason}
                                                    onChange={(e) => setReturnReason(e.target.value)}>
                                                <option value="">Vyberte důvod…</option>
                                                {RETURN_REASON_OPTIONS.map((r) => (
                                                    <option key={r.value} value={r.value}>{r.label}</option>
                                                ))}
                                            </select>
                                        </div>
                                        <div className="col-md-6">
                                            <label className="form-label" htmlFor="creditNoteNumber">
                                                Číslo dobropisu <span className="text-muted small">(volitelné)</span>
                                            </label>
                                            <input type="text" id="creditNoteNumber" className="form-control"
                                                   value={creditNoteNumber} maxLength={50}
                                                   onChange={(e) => setCreditNoteNumber(e.target.value)} />
                                        </div>
                                    </div>
                                )}

                                <div className="mb-3">
                                    <label className="form-label" htmlFor="batchId">Šarže</label>
                                    <select id="batchId" className="form-select"
                                            value={batchId} onChange={(e) => setBatchId(e.target.value)}>
                                        <option value="">Vyberte šarži…</option>
                                        {availableBatches.map((b) => (
                                            <option key={b.batchId} value={b.batchId}>
                                                {b.invoiceNumber || "bez dokladu"} · zbývá{" "}
                                                {formatQuantity(b.quantityRemaining, product.unit)}
                                                {b.unitPriceExclVat != null
                                                    ? ` · ${formatCurrency(b.unitPriceExclVat)} bez DPH`
                                                    : ""}
                                            </option>
                                        ))}
                                    </select>
                                </div>

                                <div className="mb-3">
                                    <label className="form-label" htmlFor="quantity">
                                        Množství <span className="text-muted small">[{product.unit}]</span>
                                    </label>
                                    <input type="number" id="quantity" className="form-control"
                                           value={quantity} onChange={(e) => setQuantity(e.target.value)}
                                           min="0" step="0.001" />
                                    <div className="form-text">Zadejte kladné číslo — pohyb zásobu sníží.</div>
                                </div>

                                <div>
                                    <label className="form-label" htmlFor="note">Poznámka (povinná)</label>
                                    <textarea id="note" className="form-control" rows={2}
                                              value={note} onChange={(e) => setNote(e.target.value)}
                                              maxLength={500}
                                              placeholder="např. inventurní manko, rozbito při manipulaci" />
                                </div>

                                <div className="alert alert-light border mt-3 mb-0 py-2 small text-muted">
                                    <i className="bi bi-info-circle me-1"></i>
                                    Přebytek se tudy nezadává — naskladněte ho ruční příjemkou,
                                    ať má nová zásoba šarži i nákupní cenu.
                                </div>
                            </>
                        )}
        </Modal>
    );
}
