import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import PageHeader from "../components/PageHeader.jsx";
import ManualReceiptModal from "../components/ManualReceiptModal.jsx";
import ReceiptTable from "../components/ReceiptTable.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import SelectFilter from "../components/filters/SelectFilter.jsx";
import GoodsReceiptImportModal from "../components/GoodsReceiptImportModal.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import { DOCUMENT_TYPE_OPTIONS, RECEIPT_STATUS_OPTIONS } from "../api/format.js";

/**
 * Seznam příjemek (draft workflow): filtry dle stavu/typu/hledání, import PDF,
 * proklik na kontrolní obrazovku.
 */
export default function ReceiptsPage() {

    const [search, setSearch] = useState("");
    const [status, setStatus] = useState("");
    const [documentType, setDocumentType] = useState("");
    const [receipts, setReceipts] = useState([]);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(10);
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse] = useState({ totalElements: 0 });
    const [refreshKey, setRefreshKey] = useState(0);
    const [sort, setSort] = useState({ by: "createdAt", desc: true });

    const [showImport, setShowImport] = useState(false);
    const [importSaving, setImportSaving] = useState(false);
    const [importResult, setImportResult] = useState(null);
    const [importError, setImportError] = useState("");

    const [showManual, setShowManual] = useState(false);
    const [manualSaving, setManualSaving] = useState(false);
    const [manualError, setManualError] = useState("");
    const [manualForm, setManualForm] = useState({
        documentType: "INVOICE", supplierName: "", supplierRegistrationNumber: "",
    });

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    useEffect(() => {
        async function loadReceipts() {
            const params = new URLSearchParams({
                page, pageSize: size, sortBy: sort.by, sortDesc: sort.desc,
            });
            if (search) params.set("search", search);
            if (status) params.set("status", status);
            if (documentType) params.set("documentType", documentType);
            try {
                const data = await api.get(`/warehouse/receipts?${params.toString()}`);
                setReceipts(data.content ?? []);
                setTotalPages(data.totalPages ?? 0);
                setResponse(data);
            } catch (err) {
                addAlert(problemMessage(err, "Příjemky se nepodařilo načíst."), "danger");
            }
        }
        const timer = setTimeout(loadReceipts, 400);
        return () => clearTimeout(timer);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [search, status, documentType, page, size, sort, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    async function handleImportSubmit(file, docType, channel) {
        setImportSaving(true);
        setImportError("");
        try {
            const fd = new FormData();
            fd.append("file", file);
            // ISDOC nese typ dokladu uvnitř souboru a jde na vlastní endpoint
            const isdoc = channel === "ISDOC";
            if (!isdoc) fd.append("documentType", docType);
            const result = await api.upload(
                isdoc ? "/warehouse/receipts/import-isdoc" : "/warehouse/receipts/import", fd);
            setImportResult(result);
            setRefreshKey((prev) => prev + 1);
        } catch (err) {
            setImportError(problemMessage(err, "Doklad se nepodařilo zpracovat."));
        } finally {
            setImportSaving(false);
        }
    }

    async function handleManualCreate() {
        setManualSaving(true);
        setManualError("");
        try {
            const created = await api.post("/warehouse/receipts", {
                documentType: manualForm.documentType,
                supplierName: manualForm.supplierName || null,
                supplierRegistrationNumber: manualForm.supplierRegistrationNumber || null,
            });
            navigate(`/warehouse/receipts/${created.id}/review`);
        } catch (err) {
            setManualError(problemMessage(err, "Příjemku se nepodařilo založit."));
        } finally {
            setManualSaving(false);
        }
    }

    function handleImportClose() {
        const imported = importResult;
        setShowImport(false);
        setImportResult(null);
        setImportError("");
        if (imported) {
            // rovnou na kontrolní obrazovku — import je jen první krok workflow
            navigate(`/warehouse/receipts/${imported.receiptId}/review`);
        }
    }

    return (
        <div>
            <PageHeader
                title="Příjemky"
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => {
                                    setManualForm({ documentType: "INVOICE", supplierName: "", supplierRegistrationNumber: "" });
                                    setManualError("");
                                    setShowManual(true);
                                }}>
                            <i className="bi bi-pencil me-1" aria-hidden="true"></i>Nová ručně
                        </button>
                        <button className="btn btn-primary" onClick={() => setShowImport(true)}>
                            <i className="bi bi-file-earmark-arrow-down me-1" aria-hidden="true"></i>
                            Import dokladu (PDF)
                        </button>
                    </>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="receiptSearch"
                    label="Hledat příjemku"
                    placeholder="Číslo dokladu nebo dodavatel"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-md-5"
                />
                <SelectFilter
                    id="receiptStatus" label="Stav" value={status}
                    onChange={(value) => { setStatus(value); setPage(1); }}
                    options={RECEIPT_STATUS_OPTIONS} emptyLabel="Všechny stavy"
                    className="col-12 col-md-4"
                />
                <SelectFilter
                    id="receiptType" label="Typ dokladu" value={documentType}
                    onChange={(value) => { setDocumentType(value); setPage(1); }}
                    options={DOCUMENT_TYPE_OPTIONS} emptyLabel="Všechny typy"
                    className="col-12 col-md-3"
                />
            </ListToolbar>

            <ReceiptTable
                receipts={receipts}
                onOpen={(r) => navigate(`/warehouse/receipts/${r.id}/review`)}
                sort={sort}
                onSortChange={handleSortChange}
                filtered={Boolean(search) || Boolean(status) || Boolean(documentType)}
            />

            <PaginationRounded
                itemCount={response.totalElements}
                totalPages={totalPages}
                pageSize={size}
                page={page}
                handlePageCount={(e) => { setSize(Number(e.target.value)); setPage(1); }}
                handleChange={(e, value) => setPage(value)}
            />

            <GoodsReceiptImportModal
                show={showImport}
                result={importResult}
                error={importError}
                saving={importSaving}
                onSubmit={handleImportSubmit}
                onClose={handleImportClose}
                closeLabel="Zkontrolovat"
            />

            <ManualReceiptModal
                show={showManual}
                form={manualForm}
                setForm={setManualForm}
                error={manualError}
                saving={manualSaving}
                onSubmit={handleManualCreate}
                onClose={() => setShowManual(false)}
            />
        </div>
    );
}
