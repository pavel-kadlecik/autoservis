import VehicleTable from "../components/VehicleTable.jsx";
import * as React from "react";
import {useEffect, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import {useNavigate} from "react-router-dom";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";

export default function VehiclePage() {

    const [search, setSearch]         = useState("");
    const [vehicles, setVehicles]     = useState([]);
    const [page, setPage]             = useState(1);
    const [size, setSize]             = useState(10);
    const [sort, setSort]             = useState({ by: 'vin', desc: false });
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse]     = useState({totalElements: 0});
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14) — prázdno uživatel
    // čte jako „nic tu není" a podle toho jedná.
    const [loadError, setLoadError] = useState("");
    const [refreshKey, setRefreshKey] = useState(0);
    const [isActive, setIsActive]     = useState(true);
    const [stkExpiring, setStkExpiring] = useState(false);

    const navigate = useNavigate();

    useEffect(() => {
        const params = new URLSearchParams({
            page,
            pageSize: size,
            sortBy:   sort.by,
            sortDesc: sort.desc,
            activeOnly: isActive,
            stkExpiring: stkExpiring,
        });

        if (search) params.append('search', search);

        async function loadVehicles() {
            try {
                const data = await api.get(`/vehicles?${params.toString()}`);
                setVehicles(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setVehicles([]);
                setLoadError(problemMessage(err, "Vozidla se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadVehicles, 400);
        return () => clearTimeout(timer);
    }, [search, page, size, sort, isActive, stkExpiring, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    function toggleVehicleStatus() {
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
                title="Vozidla"
                actions={
                    <button className="btn btn-primary" onClick={() => navigate('/vehicles/new')}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nové vozidlo
                    </button>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="vehicleSearch"
                    label="Hledat vozidlo"
                    placeholder="VIN, SPZ, model, značka nebo zákazník"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-7"
                />
                <ToggleFilter id="vehicleStkExpiring" label="Končící STK"
                              checked={stkExpiring} onChange={(value) => { setStkExpiring(value); setPage(1); }}
                              className="col-auto pb-2" />
                <ToggleFilter id="vehicleActiveOnly" label="Jen aktivní"
                              checked={isActive} onChange={(value) => { setIsActive(value); setPage(1); }}
                              className="col-auto pb-2" />
            </ListToolbar>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setRefreshKey(prev => prev + 1)} />
            ) : (
                <>
                    <VehicleTable
                        vehicles={vehicles}
                        toggleStatus={toggleVehicleStatus}
                        sort={sort}
                        onSortChange={handleSortChange}
                        filtered={Boolean(search) || !isActive || stkExpiring}
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
