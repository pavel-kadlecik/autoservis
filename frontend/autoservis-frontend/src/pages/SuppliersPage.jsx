import { useEffect, useState } from "react";
import * as React from "react";
import { api, problemMessage } from "../api/api.js";
import SupplierTable from "../components/SupplierTable.jsx";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";

export default function SuppliersPage() {

    const [search, setSearch]         = useState("");
    const [suppliers, setSuppliers]   = useState([]);
    const [response, setResponse]     = useState({ totalElements: 0 });
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14) — prázdno uživatel
    // čte jako „nic tu není" a podle toho jedná.
    const [loadError, setLoadError] = useState("");
    const [page, setPage]             = useState(1);
    const [size, setSize]             = useState(10);
    const [totalPages, setTotalPages] = useState(0);
    const [isActive, setIsActive]     = useState(true);
    const [refreshKey, setRefreshKey] = useState(0);
    const [sort, setSort]             = useState({ by: null, desc: false });

    useEffect(() => {

        const params = new URLSearchParams({
            page,
            pageSize:   size,
            sortDesc:   sort.desc,
            activeOnly: isActive,
        });

        if (sort.by) params.append("sortBy", sort.by);

        if (search) params.append("search", search);

        async function loadSuppliers() {
            try {
                const data = await api.get(`/warehouse/suppliers?${params.toString()}`);
                setSuppliers(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setSuppliers([]);
                setLoadError(problemMessage(err, "Dodavatele se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadSuppliers, 400);
        return () => clearTimeout(timer);

    }, [search, page, size, sort, isActive, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

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

    return (
        <div>
            <PageHeader
                title="Dodavatelé"
                subtitle="Dodavatelé vznikají importem dokladu, ručně se nezakládají."
            />

            <ListToolbar>
                <SearchFilter
                    id="supplierSearch"
                    label="Hledat dodavatele"
                    placeholder="Název, IČO nebo město"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-8"
                />
                <ToggleFilter id="supplierActiveOnly" label="Jen aktivní"
                              checked={isActive} onChange={(value) => { setIsActive(value); setPage(1); }}
                              className="col-auto pb-2" />
            </ListToolbar>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setRefreshKey(prev => prev + 1)} />
            ) : (
                <>
                    <SupplierTable
                        suppliers={suppliers}
                        toggleStatus={toggleStatus}
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
