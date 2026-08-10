import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { useAlert } from "../context/AlertContext.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import FormModal from "../components/FormModal.jsx";
import ReceiptDraftHeaderForm from "../components/ReceiptDraftHeaderForm.jsx";
import ReceiptDraftLinesTable from "../components/ReceiptDraftLinesTable.jsx";
import {
    getDocumentTypeLabel,
    getReceiptStatusTone,
    getReceiptStatusLabel,
    RECEIPT_CHECK_LABELS,
} from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import LoadingState from "../components/LoadingState.jsx";
import ErrorState from "../components/ErrorState.jsx";

// Hlavičková pole s číselnou hodnotou (parsují se z inputu na number).
const NUMERIC_HEADER_FIELDS = new Set(["subtotal", "vatAmount", "totalAmount"]);
// Řádková pole s číselnou hodnotou; vatRate je celé číslo.
const NUMERIC_LINE_FIELDS = new Set(["quantity", "unitPriceExclVat", "totalExclVat", "totalInclVat", "vatRate"]);

/**
 * Kontrolní obrazovka příjemky (PENDING_REVIEW): editace draftu s barevnými
 * stavy polí, náhled PDF, deterministické kontroly, potvrzení (naskladnění)
 * nebo zamítnutí. Pro CONFIRMED/REJECTED zobrazuje zmrazený snapshot read-only.
 */
export default function ReceiptReviewPage() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [detail, setDetail] = useState(null);
    const [draft, setDraft] = useState(null);          // pracovní kopie draftu
    const [dirty, setDirty] = useState(false);
    const [pdfUrl, setPdfUrl] = useState(null);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");
    const [showConfirm, setShowConfirm] = useState(false);
    const [showReject, setShowReject] = useState(false);
    const [showCancel, setShowCancel] = useState(false);

    // Náhled originálu jde sbalit na svislý pruh u pravého okraje — při kontrole
    // dlouhého dokladu je potřeba šířka na řádky. Volba přežije i další doklady.
    const [pdfOpen, setPdfOpen] = useState(
        () => localStorage.getItem("receipt.pdfPreview") !== "collapsed");

    useEffect(() => {
        localStorage.setItem("receipt.pdfPreview", pdfOpen ? "open" : "collapsed");
    }, [pdfOpen]);

    const readOnly = detail?.status !== "PENDING_REVIEW";

    useEffect(() => {
        let cancelled = false;
        api.get(`/warehouse/receipts/${id}`)
            .then((data) => {
                if (cancelled) return;
                setDetail(data);
                setDraft(data.draft);
                setDirty(false);
            })
            .catch((err) => setError(problemMessage(err, "Příjemku se nepodařilo načíst.")));
        return () => { cancelled = true; };
    }, [id]);

    useEffect(() => {
        if (!detail?.hasPdf) return undefined;
        let url = null;
        api.getBlob(`/warehouse/receipts/${id}/pdf`).then((objectUrl) => {
            url = objectUrl;
            setPdfUrl(objectUrl);
        });
        return () => { if (url) URL.revokeObjectURL(url); };
    }, [id, detail?.hasPdf]);

    function parseValue(raw, numeric, integer = false) {
        if (raw === "" || raw == null) return null;
        if (!numeric) return raw;
        const n = integer ? parseInt(raw, 10) : Number(raw);
        return Number.isNaN(n) ? null : n;
    }

    function updateHeaderField(name, raw) {
        setDraft((prev) => ({
            ...prev,
            header: {
                ...prev.header,
                [name]: { value: parseValue(raw, NUMERIC_HEADER_FIELDS.has(name)), state: "EDITED" },
            },
        }));
        setDirty(true);
    }

    function updateLineField(index, name, raw) {
        setDraft((prev) => {
            const lines = prev.lines.map((line, i) => i !== index ? line : {
                ...line,
                [name]: {
                    value: parseValue(raw, NUMERIC_LINE_FIELDS.has(name), name === "vatRate"),
                    state: "EDITED",
                },
            });
            return { ...prev, lines };
        });
        setDirty(true);
    }

    function removeLine(index) {
        setDraft((prev) => ({ ...prev, lines: prev.lines.filter((_, i) => i !== index) }));
        setDirty(true);
    }

    /** Rozhodnutí u napárovaného dodacího listu: LINKED (jen provázat) / RESTOCKED. */
    function updateDnResolution(index, resolution) {
        setDraft((prev) => ({
            ...prev,
            deliveryNoteRefs: prev.deliveryNoteRefs.map((ref, i) =>
                i !== index ? ref : { ...ref, resolution }),
        }));
        setDirty(true);
    }

    /** Editace dodavatele (jen ne-AUTO drafty — ruční příjemky, neznámý dodavatel). */
    function updateSupplierField(name, raw) {
        setDraft((prev) => ({
            ...prev,
            supplier: {
                ...prev.supplier,
                extracted: { ...(prev.supplier?.extracted ?? {}), [name]: raw === "" ? null : raw },
            },
        }));
        setDirty(true);
    }

    /** Přidá prázdný ITEM řádek (pozice přečísluje server při uložení). */
    function addLine() {
        const absent = { value: null, state: "ABSENT" };
        setDraft((prev) => ({
            ...prev,
            lines: [...(prev.lines ?? []), {
                lineKind: "ITEM",
                position: (prev.lines?.length ?? 0) + 1,
                catalogNumber: absent,
                name: absent,
                unit: { value: "ks", state: "DEFAULTED" },
                quantity: absent,
                unitPriceExclVat: absent,
                vatRate: { value: 21, state: "DEFAULTED" },
                totalExclVat: absent,
                totalInclVat: absent,
                productMatch: { state: "NONE", productId: null, candidates: null },
            }],
        }));
        setDirty(true);
    }

    /** Přepnutí typu řádku ITEM↔NOTE — neskladový řádek (práce, spotřební materiál) se nenaskladní. */
    function updateLineKind(index, kind) {
        setDraft((prev) => {
            const lines = prev.lines.map((line, i) => i !== index ? line : { ...line, lineKind: kind });
            return { ...prev, lines };
        });
        setDirty(true);
    }

    /** Volba skladové karty pro SUGGESTED řádek: productId, nebo "NEW" = nový produkt. */
    function updateLineMatch(index, value) {
        setDraft((prev) => {
            const lines = prev.lines.map((line, i) => i !== index ? line : {
                ...line,
                productMatch: {
                    state: "CONFIRMED",
                    productId: value === "NEW" ? null : value,
                    candidates: line.productMatch?.candidates ?? null,
                },
            });
            return { ...prev, lines };
        });
        setDirty(true);
    }

    async function saveDraft() {
        setSaving(true);
        setError("");
        try {
            const data = await api.put(`/warehouse/receipts/${id}/draft`, draft);
            setDetail(data);
            setDraft(data.draft);
            setDirty(false);
            addAlert("Koncept příjemky uložen a přepočten.", "success");
        } catch (err) {
            setError(problemMessage(err, "Koncept se nepodařilo uložit."));
        } finally {
            setSaving(false);
        }
    }

    async function confirmReceipt() {
        setShowConfirm(false);
        setSaving(true);
        setError("");
        try {
            // rozeditované změny nejdřív uložit, potvrzuje se uložený draft
            if (dirty) {
                const saved = await api.put(`/warehouse/receipts/${id}/draft`, draft);
                setDetail(saved);
                setDraft(saved.draft);
                setDirty(false);
            }
            const data = await api.post(`/warehouse/receipts/${id}/confirm`, null);
            setDetail(data);
            setDraft(data.draft);
            addAlert(`Doklad ${data.documentNumber} potvrzen a naskladněn.`, "success");
        } catch (err) {
            setError(problemMessage(err, "Příjemku se nepodařilo potvrdit."));
        } finally {
            setSaving(false);
        }
    }

    async function rejectReceipt({ note }) {
        setShowReject(false);
        setSaving(true);
        setError("");
        try {
            const data = await api.post(`/warehouse/receipts/${id}/reject`, { note: note || null });
            setDetail(data);
            setDraft(data.draft);
            addAlert(`Doklad ${data.documentNumber ?? ""} zamítnut.`, "info");
        } catch (err) {
            setError(problemMessage(err, "Příjemku se nepodařilo zamítnout."));
        } finally {
            setSaving(false);
        }
    }

    // Povinnost důvodu hlídá `FormModal` (U6.2) — dřív se kontrolovala tady
    // a hláška se ukázala mimo dialog, na stránce pod ním.
    async function cancelReceipt({ note }) {
        setShowCancel(false);
        setSaving(true);
        setError("");
        try {
            const data = await api.post(`/warehouse/receipts/${id}/cancel`, { note });
            setDetail(data);
            setDraft(data.draft);
            addAlert(`Doklad ${data.documentNumber ?? ""} stornován, zboží odepsáno ze skladu.`, "info");
        } catch (err) {
            setError(problemMessage(err, "Příjemku se nepodařilo stornovat."));
        } finally {
            setSaving(false);
        }
    }

    if (!detail && !error) return <LoadingState />;
    if (!detail) return <ErrorState message={error} backTo="/warehouse/receipts" backLabel="Zpět na příjemky" />;

    const checks = draft?.checks ?? [];
    const failedChecks = checks.filter((c) => !c.ok);

    return (
        <div>
            <PageHeader
                title={`${getDocumentTypeLabel(detail.documentType)} ${detail.documentNumber ?? ""}`.trim()}
                backTo="/warehouse/receipts"
                backLabel="Zpět na příjemky"
                badges={
                    <StatusBadge tone={getReceiptStatusTone(detail.status)}>
                        {getReceiptStatusLabel(detail.status)}
                    </StatusBadge>
                }
                actions={detail.status === "CONFIRMED" && (
                    <button className="btn btn-outline-danger" disabled={saving}
                            onClick={() => setShowCancel(true)}>
                        Stornovat
                    </button>
                )}
            />

            {error && <div className="alert alert-danger py-2">{error}</div>}

            {detail.status === "CANCELLED" && (
                <div className="alert alert-warning py-2">
                    Doklad byl stornován — zboží bylo odepsáno ze skladu kompenzačními pohyby.
                    {detail.cancellationNote && <> Důvod: {detail.cancellationNote}</>}
                </div>
            )}

            {detail.status === "REJECTED" && detail.rejectionNote && (
                <div className="alert alert-secondary py-2">
                    Důvod zamítnutí: {detail.rejectionNote}
                </div>
            )}

            {(draft?.deliveryNoteRefs ?? []).map((ref, i) => ref.matchedReceiptId != null && (
                <div key={ref.number} className="alert alert-warning py-2">
                    <div className="mb-1">
                        Faktura kryje dodací list <strong>{ref.number}</strong>, který už je
                        naskladněn (<a href={`/warehouse/receipts/${ref.matchedReceiptId}/review`}
                                       target="_blank" rel="noreferrer">příjemka #{ref.matchedReceiptId}</a>).
                        Bez rozhodnutí nejde doklad potvrdit.
                    </div>
                    <div className="form-check form-check-inline">
                        <input type="radio" id={`dn-${i}-linked`} className="form-check-input"
                               name={`dnRef-${i}`} checked={ref.resolution === "LINKED"}
                               disabled={readOnly}
                               onChange={() => updateDnResolution(i, "LINKED")} />
                        <label className="form-check-label" htmlFor={`dn-${i}-linked`}>
                            Pouze provázat (nenaskladňovat znovu)
                        </label>
                    </div>
                    <div className="form-check form-check-inline">
                        <input type="radio" id={`dn-${i}-restocked`} className="form-check-input"
                               name={`dnRef-${i}`} checked={ref.resolution === "RESTOCKED"}
                               disabled={readOnly}
                               onChange={() => updateDnResolution(i, "RESTOCKED")} />
                        <label className="form-check-label" htmlFor={`dn-${i}-restocked`}>
                            Naskladnit znovu
                        </label>
                    </div>
                </div>
            ))}

            {failedChecks.length > 0 && (
                <div className="alert alert-warning py-2">
                    <div className="fw-semibold mb-1">Neprošlé kontroly:</div>
                    <ul className="mb-0">
                        {failedChecks.map((c, i) => (
                            <li key={`${c.code}-${i}`}>
                                {RECEIPT_CHECK_LABELS[c.code] ?? c.code}
                                {c.position != null ? ` (řádek ${c.position})` : ""}
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            <div className="row g-3">
                <div className={detail.hasPdf && pdfOpen ? "col-lg-7" : "col"}>
                    {draft ? (
                        <>
                            <ReceiptDraftHeaderForm header={draft.header}
                                                    supplier={draft.supplier}
                                                    readOnly={readOnly}
                                                    onChange={updateHeaderField}
                                                    onSupplierChange={updateSupplierField} />
                            {!readOnly && (
                                <div className="small text-muted mb-2 d-flex flex-wrap gap-3 align-items-center">
                                    <span><i className="bi bi-check-circle-fill text-success" /> ověřeno</span>
                                    <span><i className="bi bi-calculator text-warning" /> dopočteno – zkontrolujte</span>
                                    <span><i className="bi bi-dash-circle text-secondary" /> není na dokladu</span>
                                    <span><span className="border border-danger rounded px-1 text-danger">rámeček</span> povinné – doplňte</span>
                                </div>
                            )}
                            <ReceiptDraftLinesTable lines={draft.lines ?? []}
                                                    readOnly={readOnly}
                                                    onChange={updateLineField}
                                                    onRemove={removeLine}
                                                    onMatchChange={updateLineMatch}
                                                    onKindChange={updateLineKind} />
                            {!readOnly && (
                                <button type="button" className="btn btn-sm btn-outline-secondary mb-2"
                                        onClick={addLine}>
                                    <i className="bi bi-plus-lg" /> Přidat řádek
                                </button>
                            )}
                        </>
                    ) : (
                        <div className="alert alert-secondary">Příjemka nemá draft.</div>
                    )}

                    {!readOnly && (
                        // §10.8: průběžné uložení je neutrální, vrcholná nevratná akce zelená
                        <div className="d-flex gap-2 mt-2">
                            <button className="btn btn-outline-secondary" disabled={saving || !dirty}
                                    onClick={saveDraft}>
                                Uložit koncept
                            </button>
                            <button className="btn btn-success" disabled={saving}
                                    onClick={() => setShowConfirm(true)}>
                                Potvrdit a naskladnit
                            </button>
                            <button className="btn btn-outline-danger ms-auto" disabled={saving}
                                    onClick={() => setShowReject(true)}>
                                Zamítnout
                            </button>
                        </div>
                    )}
                </div>

                {detail.hasPdf && pdfOpen && (
                    <div className="col-lg-5">
                        <div className="d-flex align-items-center justify-content-between mb-2">
                            <span className="text-muted small text-uppercase">Originál dokladu</span>
                            <button type="button" className="btn btn-sm btn-outline-secondary"
                                    onClick={() => setPdfOpen(false)}>
                                <i className="bi bi-chevron-double-right me-1" aria-hidden="true"></i>Skrýt náhled
                            </button>
                        </div>
                        {pdfUrl
                            ? <iframe src={pdfUrl} title="Originál dokladu"
                                      style={{ width: "100%", height: "80vh", border: "1px solid #dee2e6" }} />
                            : <LoadingState label="Načítám PDF…" inline />}
                    </div>
                )}

                {detail.hasPdf && !pdfOpen && (
                    <div className="col-auto">
                        <button type="button" className="btn btn-outline-secondary pdf-rail"
                                onClick={() => setPdfOpen(true)}
                                title="Zobrazit náhled dokladu">
                            <i className="bi bi-file-earmark-pdf" aria-hidden="true"></i>
                            <span className="ms-2">Náhled dokladu</span>
                        </button>
                    </div>
                )}
            </div>

            <ConfirmDialog
                show={showConfirm}
                title="Potvrdit příjemku?"
                message="Doklad se potvrdí a položky se naskladní — vzniknou šarže a skladové pohyby. Akce je nevratná."
                yesLabel="Potvrdit a naskladnit"
                noLabel="Zpět"
                onConfirm={confirmReceipt}
                onCancel={() => setShowConfirm(false)}
            />

            <FormModal
                show={showReject}
                title="Zamítnout příjemku?"
                intro={
                    <p>Doklad se označí jako zamítnutý — nic se nenaskladní a číslo dokladu
                        půjde importovat znovu.</p>
                }
                fields={[{ name: "note", label: "Důvod", type: "textarea", maxLength: 500 }]}
                submitLabel="Zamítnout"
                onSubmit={rejectReceipt}
                onCancel={() => setShowReject(false)}
                saving={saving}
            />

            <FormModal
                show={showCancel}
                title="Stornovat příjemku?"
                intro={
                    <>
                        <p>Naskladněné zboží se odepíše kompenzačními pohyby a číslo dokladu
                            půjde importovat znovu. Původní pohyby zůstanou v historii.</p>
                        <p className="text-muted small mb-2">
                            Stornovat lze jen příjemku, ze které se ještě nevydávalo.
                        </p>
                    </>
                }
                fields={[{
                    name: "note", label: "Důvod", type: "textarea", maxLength: 500, required: true,
                    requiredMessage: "Důvod storna je povinný.",
                }]}
                submitLabel="Stornovat"
                onSubmit={cancelReceipt}
                onCancel={() => setShowCancel(false)}
                saving={saving}
            />
        </div>
    );
}
