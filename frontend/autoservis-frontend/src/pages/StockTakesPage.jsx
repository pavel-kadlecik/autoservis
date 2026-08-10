import * as React from "react";
import {useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import {api, problemMessage} from "../api/api.js";
import {formatDateTime, getStockTakeStatusLabel, getStockTakeStatusTone, STOCK_TAKE_STATUS_OPTIONS} from "../api/format.js";
import {useAlert} from "../context/AlertContext.jsx";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import EmptyState from "../components/EmptyState.jsx";
import DataTable from "../components/DataTable.jsx";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import SelectFilter from "../components/filters/SelectFilter.jsx";

/**
 * Seznam inventur a založení nové (E6.4, P-5).
 * Otevřená smí být jen jedna — server vrací 409 STOCK_TAKE_ALREADY_OPEN.
 *
 * Výpis je stránkovaný na serveru (`PagedResponse`), řazení jde přes whitelist
 * v `StockTakeMapper.xml` — stejný vzor jako ostatní seznamy (zakázky, faktury).
 */
export default function StockTakesPage() {

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [stockTakes, setStockTakes] = useState([]);
    const [search, setSearch] = useState("");
    const [status, setStatus] = useState("");
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(10);
    const [sort, setSort] = useState({ by: 'openedAt', desc: true });
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse] = useState({ totalElements: 0 });

    const [opening, setOpening] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        const params = new URLSearchParams({
            page, pageSize: size, sortBy: sort.by, sortDesc: sort.desc,
        });
        if (search) params.append('search', search);
        if (status) params.append('status', status);

        async function load() {
            const data = await api.get(`/warehouse/stock-takes?${params.toString()}`);
            setStockTakes(data.content);
            setTotalPages(data.totalPages);
            setResponse(data);
        }
        const timer = setTimeout(load, 400);
        return () => clearTimeout(timer);
    }, [search, status, page, size, sort]);

    function handleStatusChange(value) {
        setStatus(value);
        setPage(1);
    }

    async function openStockTake() {
        setOpening(true);
        setError("");
        try {
            const created = await api.post("/warehouse/stock-takes", { note: null });
            addAlert("Inventura byla otevřena — soupis je připraven k vyplnění.", "success");
            navigate(`/warehouse/stock-takes/${created.id}`);
        } catch (err) {
            setError(problemMessage(err, "Inventuru se nepodařilo otevřít."));
        } finally {
            setOpening(false);
        }
    }

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

    const columns = [
        {
            key: "stockTakeNumber", header: "Číslo inventury", sortable: true,
            render: t => <span className="font-monospace text-muted small">{t.stockTakeNumber}</span>,
        },
        {
            key: "status", header: "Stav", sortable: true,
            render: t => (
                <StatusBadge tone={getStockTakeStatusTone(t.status)}>
                    {getStockTakeStatusLabel(t.status)}
                </StatusBadge>
            ),
        },
        {
            key: "openedAt", header: "Zahájena", sortable: true,
            render: t => formatDateTime(t.openedAt),
        },
        {
            key: "closedAt", header: "Uzavřena", sortable: true,
            render: t => (t.closedAt ? formatDateTime(t.closedAt) : "—"),
        },
        {
            key: "note", header: "Poznámka", sortable: true, className: "text-muted",
            render: t => t.note || "—",
        },
    ];

    return (
        <div>
            <PageHeader
                title="Inventury"
                actions={
                    <button className="btn btn-primary" onClick={openStockTake} disabled={opening}>
                        {opening ? "Otevírám…" : "Zahájit inventuru"}
                    </button>
                }
            />

            {error && <div className="alert alert-danger py-2">{error}</div>}

            <ListToolbar>
                <SearchFilter
                    id="stockTakeSearch"
                    label="Hledat inventuru"
                    placeholder="Číslo inventury nebo poznámka"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-8"
                />
                <SelectFilter
                    id="stockTakeStatusFilter"
                    label="Stav"
                    value={status}
                    onChange={handleStatusChange}
                    options={STOCK_TAKE_STATUS_OPTIONS}
                    emptyLabel="Všechny stavy"
                    className="col-12 col-xl-4"
                />
            </ListToolbar>

            <DataTable
                columns={columns}
                rows={stockTakes}
                sort={sort}
                onSortChange={handleSortChange}
                onRowClick={t => navigate(`/warehouse/stock-takes/${t.id}`)}
                emptyState={
                    (search || status)
                        ? <EmptyState icon="clipboard-check"
                                      title="Filtru neodpovídá žádná inventura."
                                      hint="Zkuste hledaný výraz zkrátit nebo zrušit filtr stavu." />
                        : <EmptyState icon="clipboard-check"
                                      title="Zatím žádná inventura neproběhla."
                                      hint="Novou zahájíte tlačítkem nahoře." />
                }
            />

            <PaginationRounded
                itemCount={response.totalElements}
                totalPages={totalPages}
                pageSize={size}
                page={page}
                handlePageCount={handlePageSizeChange}
                handleChange={handlePageChange}
            />
        </div>
    );
}
