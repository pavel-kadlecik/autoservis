import React, { useEffect, useState } from "react";
import { formatCurrency, RECEIPT_CHECK_LABELS } from "../api/format.js";
import Modal from "./Modal.jsx";
import RequiredMark from "./RequiredMark.jsx";

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB — stejný limit jako Spring multipart (application.yaml)

/**
 * Bootstrap modal pro import dokladu dodavatele z PDF (POST /warehouse/receipts/import).
 * Typ dokladu (faktura / dodací list) volí uživatel — ne AI.
 *
 * Dvě fáze:
 *  - výběr typu dokladu + souboru a odeslání,
 *  - po úspěšném importu souhrn draftu (ReceiptDraftDto.ImportResponse):
 *    doklad je uložen jako PENDING_REVIEW, nic se nenaskladnilo — produkty,
 *    šarže a pohyby vzniknou až potvrzením v kontrolní obrazovce (fáze 3+4).
 *
 * @param {boolean}  show           - zda je modal viditelný
 * @param {Object}   [result]       - ReceiptDraftDto.ImportResponse po úspěšném importu; null před odesláním
 * @param {string}   [error]        - chybová zpráva k zobrazení v modalu
 * @param {boolean}  saving         - true během probíhajícího requestu (blokuje tlačítka)
 * @param {Function} onSubmit(file, documentType) - zavolá se po kliknutí na "Nahrát a zpracovat"
 * @param {Function} onClose()      - zavolá se při zavření modalu
 * @param {string}   [closeLabel]   - popisek tlačítka po úspěchu (default "Zavřít")
 */
export default function GoodsReceiptImportModal({ show, result, error, saving, onSubmit, onClose,
                                                  closeLabel = "Zavřít" }) {

    const [file, setFile] = useState(null);
    const [documentType, setDocumentType] = useState("INVOICE");
    const [channel, setChannel] = useState("PDF");   // PDF (AI extrakce) | ISDOC (strojová data)
    const [clientError, setClientError] = useState("");

    const isIsdoc = channel === "ISDOC";

    // Reset vnitřního stavu při každém otevření modalu (ne při zavření —
    // ať uživatel při omylem zavřeném modalu nepřijde o právě vybraný soubor).
    useEffect(() => {
        if (show) {
            setFile(null);
            setDocumentType("INVOICE");
            setChannel("PDF");
            setClientError("");
        }
    }, [show]);

    if (!show) return null;

    /** Přepnutí kanálu zahodí vybraný soubor — jinak by zůstal PDF u ISDOC. */
    function handleChannelChange(value) {
        setChannel(value);
        setFile(null);
        setClientError("");
    }

    function handleFileChange(e) {
        const selected = e.target.files[0] ?? null;
        setClientError("");

        const name = selected ? selected.name.toLowerCase() : "";
        const okExtension = isIsdoc
            ? (name.endsWith(".isdoc") || name.endsWith(".isdocx") || name.endsWith(".xml"))
            : [".pdf", ".jpg", ".jpeg", ".png", ".heic", ".webp"].some((ext) => name.endsWith(ext));
        if (selected && !okExtension) {
            setClientError(isIsdoc
                ? "Vyberte prosím soubor ve formátu ISDOC (.isdoc nebo .xml)."
                : "Vyberte prosím PDF nebo fotku dokladu (JPG, PNG, HEIC).");
            setFile(null);
            return;
        }
        if (selected && selected.size > MAX_FILE_SIZE_BYTES) {
            setClientError("Soubor je příliš velký (limit 10 MB).");
            setFile(null);
            return;
        }
        setFile(selected);
    }

    function handleSubmit() {
        if (!file) return;
        // u ISDOC je typ dokladu uvnitř souboru — parametr se neposílá
        onSubmit(file, isIsdoc ? null : documentType, channel);
    }

    const hasResult = Boolean(result);
    const failedChecks = (result?.checks ?? []).filter(c => !c.ok);

    return (
        <Modal show={show} size="modal-lg" onClose={onClose} closable={!saving}
               title={hasResult ? "Doklad uložen ke kontrole"
                   : `Import dokladu dodavatele (${isIsdoc ? "ISDOC" : "PDF"})`}
               footer={
                   hasResult ? (
                       <button type="button" className="btn btn-primary" onClick={onClose}>
                           {closeLabel}
                       </button>
                   ) : (
                       <>
                           <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={saving}>
                               Zrušit
                           </button>
                           <button type="button" className="btn btn-primary" onClick={handleSubmit}
                                   disabled={saving || !file}>
                               {saving ? "Zpracovávám…" : "Nahrát a zpracovat"}
                           </button>
                       </>
                   )
               }>
                    <div>
                        {!hasResult && (
                            <>
                                <p className="text-muted">
                                    Nahrajte doklad od dodavatele. Údaje se přečtou automaticky
                                    a doklad se uloží ke kontrole — nic se zatím nenaskladňuje.
                                </p>

                                <div className="mb-3">
                                    <label className="form-label d-block">Formát dokladu</label>
                                    <div className="form-check form-check-inline">
                                        <input type="radio" id="channelPdf" className="form-check-input"
                                               name="channel" value="PDF" checked={!isIsdoc}
                                               onChange={() => handleChannelChange("PDF")} disabled={saving} />
                                        <label className="form-check-label" htmlFor="channelPdf">
                                            PDF nebo fotka (přečte AI)
                                        </label>
                                    </div>
                                    <div className="form-check form-check-inline">
                                        <input type="radio" id="channelIsdoc" className="form-check-input"
                                               name="channel" value="ISDOC" checked={isIsdoc}
                                               onChange={() => handleChannelChange("ISDOC")} disabled={saving} />
                                        <label className="form-check-label" htmlFor="channelIsdoc">
                                            ISDOC (elektronická faktura)
                                        </label>
                                    </div>
                                    {isIsdoc && (
                                        <div className="form-text">
                                            ISDOC obsahuje přesná strojová data — nic se nedohaduje.
                                            Typ dokladu je uvnitř souboru. Dobropisy zatím nelze naskladnit.
                                        </div>
                                    )}
                                </div>

                                <div className="mb-3" hidden={isIsdoc}>
                                    <label className="form-label d-block">
                                        Typ dokladu <RequiredMark />
                                    </label>
                                    <div className="form-check form-check-inline">
                                        <input type="radio" id="docTypeInvoice" className="form-check-input"
                                               name="documentType" value="INVOICE"
                                               checked={documentType === "INVOICE"}
                                               onChange={() => setDocumentType("INVOICE")} disabled={saving} />
                                        <label className="form-check-label" htmlFor="docTypeInvoice">
                                            Faktura (daňový doklad)
                                        </label>
                                    </div>
                                    <div className="form-check form-check-inline">
                                        <input type="radio" id="docTypeDeliveryNote" className="form-check-input"
                                               name="documentType" value="DELIVERY_NOTE"
                                               checked={documentType === "DELIVERY_NOTE"}
                                               onChange={() => setDocumentType("DELIVERY_NOTE")} disabled={saving} />
                                        <label className="form-check-label" htmlFor="docTypeDeliveryNote">
                                            Dodací list (bez rozpisu DPH)
                                        </label>
                                    </div>
                                    {documentType === "DELIVERY_NOTE" && (
                                        <div className="form-text">
                                            Dodací list neobsahuje rozpis DPH — sazba 21 % se doplní
                                            automaticky a půjde upravit při kontrole.
                                        </div>
                                    )}
                                </div>
                                <div className="mb-3">
                                    <label className="form-label" htmlFor="invoiceFile">
                                        Soubor dokladu <RequiredMark />
                                    </label>
                                    <input type="file" id="invoiceFile" className="form-control"
                                           accept={isIsdoc ? ".isdoc,.isdocx,.xml"
                                               : "application/pdf,image/jpeg,image/png,image/heic,image/webp"}
                                           onChange={handleFileChange} disabled={saving} />
                                </div>
                                {(clientError || error) && (
                                    <div className="alert alert-danger py-2">{clientError || error}</div>
                                )}
                            </>
                        )}

                        {hasResult && (
                            <>
                                <div className="alert alert-info py-2">
                                    Doklad je uložen ke kontrole — na sklad se nic nenaskladnilo.
                                    Naskladnění proběhne až po potvrzení.
                                </div>
                                {!result.reconciliationOk && (
                                    <div className="alert alert-warning py-2">
                                        <div className="fw-semibold mb-1">
                                            Kontrolní součty nesedí — doklad vyžaduje ruční kontrolu:
                                        </div>
                                        <ul className="mb-0">
                                            {failedChecks.map((c, idx) => (
                                                <li key={`${c.code}-${idx}`}>
                                                    {RECEIPT_CHECK_LABELS[c.code] ?? c.code}
                                                    {c.position != null ? ` (řádek ${c.position})` : ""}
                                                </li>
                                            ))}
                                        </ul>
                                    </div>
                                )}
                                {!result.supplierMatched && (
                                    <div className="alert alert-secondary py-2">
                                        Dodavatel zatím není v databázi — založí se při potvrzení dokladu.
                                    </div>
                                )}
                                <div className="row g-2 mb-3">
                                    <div className="col-md-4">
                                        <div className="text-muted small">Dodavatel</div>
                                        <div>{result.supplierName ?? "—"}</div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="text-muted small">
                                            {result.documentType === "DELIVERY_NOTE" ? "Číslo dodacího listu" : "Číslo faktury"}
                                        </div>
                                        <div>{result.documentNumber ?? "—"}</div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="text-muted small">Zakázka</div>
                                        <div>{result.orderNumber ?? "—"}</div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="text-muted small">Stav</div>
                                        <div>Čeká na kontrolu</div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="text-muted small">Celková částka s DPH</div>
                                        <div>{formatCurrency(result.totalAmount)}</div>
                                    </div>
                                </div>

                                <div className="table-responsive">
                                <table className="table table-sm">
                                    <thead>
                                        <tr>
                                            <th scope="col">SKU</th>
                                            <th scope="col">Název</th>
                                            <th scope="col" className="text-end">Množství</th>
                                            <th scope="col" className="text-end">Cena bez DPH</th>
                                            <th scope="col" className="text-end">DPH %</th>
                                            <th scope="col" className="text-end">Celkem s DPH</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {result.items.map((line, idx) => (
                                            <tr key={line.sku ?? idx}>
                                                <td>{line.sku ?? "—"}</td>
                                                <td>{line.name}</td>
                                                <td className="text-end">{line.quantity}</td>
                                                <td className="text-end">{formatCurrency(line.unitPriceExclVat)}</td>
                                                <td className="text-end">{line.vatRate ?? "—"}</td>
                                                <td className="text-end">{formatCurrency(line.totalInclVat)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                                </div>
                            </>
                        )}
                    </div>
        </Modal>
    );
}
