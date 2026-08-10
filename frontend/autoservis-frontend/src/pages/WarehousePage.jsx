import * as React from "react";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { formatCurrency } from "../api/format.js";
import LoadErrorState from "../components/LoadErrorState.jsx";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import ProductTable from "../components/ProductTable.jsx";
import GoodsReceiptImportModal from "../components/GoodsReceiptImportModal.jsx";
import { useAlert } from "../context/AlertContext.jsx";

export default function WarehousePage() {

    const [search, setSearch]         = useState("");
    const [products, setProducts]     = useState([]);
    const [page, setPage]             = useState(1);
    const [size, setSize]             = useState(10);
    const [sort, setSort]             = useState({ by: "name", desc: false });
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse]     = useState({ totalElements: 0 });
    const [isActive, setIsActive]     = useState(true);
    const [lowStockOnly, setLowStockOnly] = useState(false);
    const [refreshKey, setRefreshKey] = useState(0);
    const [valuation, setValuation] = useState({ totalValue: null, items: [] });
    // Chyba načtení odlišená od prázdna (KN-14); ocenění má vlastní příznak, protože
    // je to samostatný dotaz a jeho selhání neznamená, že seznam karet chybí.
    const [loadError, setLoadError] = useState("");
    const [valuationFailed, setValuationFailed] = useState(false);

    const [showImport, setShowImport] = useState(false);
    const [importSaving, setImportSaving] = useState(false);
    const [importError, setImportError] = useState("");
    const [importResult, setImportResult] = useState(null);

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    useEffect(() => {
        const params = new URLSearchParams({
            page,
            pageSize: size,
            sortBy:   sort.by,
            sortDesc: sort.desc,
            activeOnly: isActive,
            lowStockOnly: lowStockOnly,
        });

        if (search) params.append("search", search);

        async function loadProducts() {
            try {
                const data = await api.get(`/warehouse/products?${params.toString()}`);
                setProducts(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setProducts([]);
                setLoadError(problemMessage(err, "Skladové karty se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadProducts, 400);
        return () => clearTimeout(timer);
    }, [search, page, size, sort, isActive, lowStockOnly, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    // Ocenění zásob: jeden fetch pro celý sklad (souhrn i hodnoty řádků) —
    // nezávisí na stránkování ani filtrech, obnovuje se jen po změně stavu skladu.
    useEffect(() => {
        async function loadValuation() {
            try {
                setValuation(await api.get("/warehouse/stock-valuation"));
                setValuationFailed(false);
            } catch {
                // Nepovedené ocenění nesmí vypadat jako sklad za nula korun (KN-14):
                // hodnota se skryje a řekne se proč. Hláška je jen tady, ne toastem —
                // je to doplňkový údaj nad seznamem, ne obsah stránky.
                setValuation({ totalValue: null, items: [] });
                setValuationFailed(true);
            }
        }
        loadValuation();
    }, [refreshKey]);

    const valueByProduct = useMemo(
        () => new Map((valuation.items ?? []).map(item => [item.productId, item.stockValue])),
        [valuation]
    );

    function toggleStatus() {
        setRefreshKey(prev => prev + 1);
    }

    function handlePageChange(event, value) {
        setPage(value);
    }

    function handlePageSizeChange(event) {
        setSize(Number(event.target.value));
        setPage(1);
    }

    async function handleImportSubmit(file, documentType, channel) {
        setImportSaving(true);
        setImportError("");
        try {
            const fd = new FormData();
            fd.append("file", file);
            // ISDOC nese typ dokladu uvnitř souboru a jde na vlastní endpoint
            const isdoc = channel === "ISDOC";
            if (!isdoc) fd.append("documentType", documentType);
            const result = await api.upload(
                isdoc ? "/warehouse/receipts/import-isdoc" : "/warehouse/receipts/import", fd);
            setImportResult(result);
        } catch (err) {
            const message = problemMessage(err, "Doklad se nepodařilo zpracovat.");
            setImportError(message);
        } finally {
            setImportSaving(false);
        }
    }

    function handleImportClose() {
        // Import ukládá jen draft (PENDING_REVIEW) — stav skladu se nemění,
        // seznam produktů proto není potřeba obnovovat.
        if (importResult) {
            addAlert(`Doklad ${importResult.documentNumber ?? ""} byl uložen ke kontrole.`, "success");
        }
        setShowImport(false);
        setImportResult(null);
        setImportError("");
    }

    return (
        <div>
            <PageHeader
                title="Sklad"
                actions={
                    <>
                        <button className="btn btn-outline-secondary" onClick={() => setShowImport(true)}>
                            <i className="bi bi-file-earmark-arrow-down me-1" aria-hidden="true"></i>
                            Import dokladu (PDF)
                        </button>
                        <button className="btn btn-primary" onClick={() => navigate('/warehouse/new')}>
                            <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nová skladová položka
                        </button>
                    </>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="productSearch"
                    label="Hledat díl"
                    placeholder="SKU nebo název dílu"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-7"
                />
                <ToggleFilter id="productLowStock" label="Nízká dostupnost"
                              checked={lowStockOnly} onChange={(value) => { setLowStockOnly(value); setPage(1); }}
                              className="col-auto pb-2" />
                <ToggleFilter id="productActiveOnly" label="Jen aktivní"
                              checked={isActive} onChange={(value) => { setIsActive(value); setPage(1); }}
                              className="col-auto pb-2" />
            </ListToolbar>

            <div className="p-3 mb-3 rounded-3 d-flex align-items-baseline gap-2"
                 style={{ background: 'var(--bs-secondary-bg, #f8f9fa)' }}>
                <span className="text-muted small">Hodnota zásob (nákupní, bez DPH)</span>
                <span className="h5 mb-0 fw-medium ms-auto">
                    {valuationFailed
                        ? <span className="text-muted fs-6 fw-normal">
                              <i className="bi bi-exclamation-triangle me-1" aria-hidden="true"></i>
                              nepodařilo se načíst
                          </span>
                        : formatCurrency(valuation.totalValue ?? 0)}
                </span>
            </div>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setRefreshKey(prev => prev + 1)} />
            ) : (
                <>
                    <ProductTable products={products} toggleStatus={toggleStatus}
                                  valueByProduct={valueByProduct}
                                  sort={sort} onSortChange={handleSortChange}
                                  filtered={Boolean(search) || !isActive || lowStockOnly} />

                    <PaginationRounded
                        itemCount={response.totalElements}
                        totalPages={totalPages}
                        pageSize={size}
                        page={page}
                        handlePageCount={handlePageSizeChange}
                        handleChange={handlePageChange}
                    />
                </>
            )}

            <GoodsReceiptImportModal
                show={showImport}
                result={importResult}
                error={importError}
                saving={importSaving}
                onSubmit={handleImportSubmit}
                onClose={handleImportClose}
            />
        </div>
    );
}
