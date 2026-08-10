import {useEffect, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import CustomerTable from "../components/CustomerTable.jsx";
import {useNavigate} from "react-router-dom";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import * as React from "react";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";

export default function CustomersPage() {

    const [search, setSearch]       = useState("");
    const [customers, setCustomers] = useState([]);
    const [page, setPage]           = useState(1);
    const [size, setSize]           = useState(10);
    // Řazení řídí DataTable (klik na hlavičku) — backend to umí přes sortBy/sortDesc,
    // dřív to byl zamrzlý stav bez setteru (nález S-28).
    const [sort, setSort]           = useState({ by: 'lastName', desc: false });
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse]   = useState({totalElements: 0});
    const [refreshKey, setRefreshKey] = useState(0);
    const [isActive, setIsActive]   = useState(true);
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14): prázdno uživatel čte jako
    // „zákazníci nejsou" a podle toho jedná.
    const [loadError, setLoadError] = useState("");

    const navigate = useNavigate();

    useEffect(() => {
        const params = new URLSearchParams({
            page,
            pageSize:   size,
            sortBy:     sort.by,
            sortDesc:   sort.desc,
            activeOnly: isActive,
        });

        if (search) params.append('search', search);

        async function loadCustomers() {
            try {
                const data = await api.get(`/customers?${params.toString()}`);
                setCustomers(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setCustomers([]);
                setLoadError(problemMessage(err, "Zákazníky se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadCustomers, 400);
        return () => clearTimeout(timer);
    }, [search, page, size, sort, isActive, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    function toggleCustomerStatus() {
        setRefreshKey(prev => prev + 1);
    }

    function handlePageChange(event, value) {
        setPage(value);
    }

    function handlePageSizeChange(event) {
        setSize(Number(event.target.value));
        setPage(1);
    }

    return (
        <div>
            <PageHeader
                title="Zákazníci"
                actions={
                    <button className="btn btn-primary" onClick={() => navigate('/customers/new')}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nový zákazník
                    </button>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="customerSearch"
                    label="Hledat zákazníka"
                    placeholder="Jméno, příjmení nebo název firmy"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-8"
                />
                <ToggleFilter id="customerActiveOnly" label="Jen aktivní"
                              checked={isActive} onChange={(value) => { setIsActive(value); setPage(1); }}
                              className="col-auto pb-2" />
            </ListToolbar>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setRefreshKey(prev => prev + 1)} />
            ) : (
                <>
                    <CustomerTable
                        customers={customers}
                        toggleStatus={toggleCustomerStatus}
                        sort={sort}
                        onSortChange={handleSortChange}
                        filtered={Boolean(search) || !isActive}
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
