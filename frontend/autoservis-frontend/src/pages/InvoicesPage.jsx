import { useEffect, useState } from "react";
import * as React from "react";
import { useSearchParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import InvoiceTable from "../components/InvoiceTable.jsx";
import { useInvoiceActions } from "../components/invoiceActions.jsx";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import { INVOICE_STATUS_OPTIONS } from "../api/format.js";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import SelectFilter from "../components/filters/SelectFilter.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";

export default function InvoicesPage() {

    const [searchParams]              = useSearchParams();
    const [search, setSearch]         = useState("");
    const [status, setStatus]         = useState("");
    const [overdue, setOverdue]       = useState(searchParams.get("overdue") === "true");
    const [invoices, setInvoices]     = useState([]);
    const [page, setPage]             = useState(1);
    const [size, setSize]             = useState(10);
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse]     = useState({ totalElements: 0 });
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14) — prázdno uživatel
    // čte jako „nic tu není" a podle toho jedná.
    const [loadError, setLoadError] = useState("");
    const [reload, setReload]         = useState(0);
    const [sort, setSort]             = useState({ by: 'issueDate', desc: true });
    // Mezery v číselné řadě (V89). Vlastní volání, ne součást výpisu — seznam se tím
    // nezpomalí. Načítá se při každé změně seznamu, ať hláška zmizí hned po nápravě.
    const [numberGaps, setNumberGaps] = useState([]);

    // Přechody stavů drží sdílený hook (invoiceActions.jsx) — tabulka je jen nabízí.
    const { run: runInvoiceAction, dialogs: invoiceDialogs } = useInvoiceActions({
        onChanged: () => setReload(r => r + 1),
        onDeleted: () => setReload(r => r + 1),
    });

    useEffect(() => {
        api.get('/invoices/number-gaps')
            .then(r => setNumberGaps(r?.enabled ? (r.missingNumbers ?? []) : []))
            .catch(() => setNumberGaps([]));   // hlídání je doplněk, jeho selhání nesmí shodit seznam
    }, [reload, invoices]);

    useEffect(() => {
        const params = new URLSearchParams({
            page, pageSize: size, sortBy: sort.by, sortDesc: sort.desc,
        });
        if (search) params.append('search', search);
        if (status) params.append('status', status);
        if (overdue) params.append('overdue', 'true');

        async function loadInvoices() {
            try {
                const data = await api.get(`/invoices?${params.toString()}`);
                setInvoices(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setInvoices([]);
                setLoadError(problemMessage(err, "Faktury se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadInvoices, 400);
        return () => clearTimeout(timer);
    }, [search, status, overdue, page, size, sort, reload]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    function handlePageChange(event, value) {
        setPage(value);
    }

    function handlePageSizeChange(event) {
        setSize(Number(event.target.value));
        setPage(1);
    }

    function handleStatusChange(value) {
        setStatus(value);
        setPage(1);
    }

    return (
        <div>
            <PageHeader title="Faktury" />

            {invoiceDialogs}

            {/* Mezera v řadě vzniká smazáním faktury, která nebyla poslední (V88/V89).
                `MAX+1` ji sám nezavře, ale číslo je při vystavení editovatelné, takže
                náprava je na jedno přepsání. Hláška proto říká přesná čísla. */}
            {numberGaps.length > 0 && (
                <div className="alert alert-warning d-flex align-items-start" role="alert">
                    <i className="bi bi-exclamation-triangle-fill me-2 mt-1" aria-hidden="true"></i>
                    <div>
                        <strong>
                            {numberGaps.length === 1
                                ? "V číselné řadě chybí faktura "
                                : "V číselné řadě chybí faktury "}
                            <span className="font-monospace">{numberGaps.join(", ")}</span>.
                        </strong>
                        <div className="small mt-1">
                            Mezera vznikla smazáním dokladu. Zavřete ji tím, že příští fakturu
                            vystavíte s {numberGaps.length === 1 ? "tímto číslem" : "těmito čísly"} místo
                            navrženého — číslo je v dialogu vystavení přepisovatelné. Upozornění
                            pak zmizí samo.
                        </div>
                    </div>
                </div>
            )}

            <ListToolbar>
                <SearchFilter
                    id="invoiceSearch"
                    label="Hledat fakturu"
                    placeholder="Číslo faktury, zákazník, VIN nebo SPZ"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-6"
                />
                <SelectFilter
                    id="statusFilter"
                    label="Stav"
                    value={status}
                    onChange={handleStatusChange}
                    options={INVOICE_STATUS_OPTIONS}
                    emptyLabel="Všechny stavy"
                    className="col-12 col-xl-3"
                />
                <SelectFilter
                    id="overdueFilter"
                    label="Splatnost"
                    value={overdue ? "true" : ""}
                    onChange={(v) => { setOverdue(v === "true"); setPage(1); }}
                    options={[{ value: "true", label: "Po splatnosti" }]}
                    emptyLabel="Vše"
                    className="col-12 col-xl-3"
                />
            </ListToolbar>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setReload(r => r + 1)} />
            ) : (
                <>
                    <InvoiceTable
                        invoices={invoices}
                        sort={sort}
                        onSortChange={handleSortChange}
                        filtered={Boolean(search) || Boolean(status) || overdue}
                        onAction={runInvoiceAction}
                    />

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
        </div>
    );
}
