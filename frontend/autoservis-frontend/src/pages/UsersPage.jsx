import {useEffect, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import UserTable from "../components/UserTable.jsx";
import {useNavigate} from "react-router-dom";
import PaginationRounded from "../components/PaginatorRounded.jsx";
import * as React from "react";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";

export default function UsersPage() {

    const [search, setSearch] = useState("");
    const [users, setUsers]   = useState([]);
    const [page, setPage]     = useState(1);
    const [size, setSize]     = useState(10);
    const [totalPages, setTotalPages] = useState(0);
    const [response, setResponse]     = useState({totalElements: 0});
    // Chyba načtení musí být odlišená od prázdného seznamu (KN-14) — prázdno uživatel
    // čte jako „nic tu není" a podle toho jedná.
    const [loadError, setLoadError] = useState("");
    const [refreshKey, setRefreshKey] = useState(0);
    const [isActive, setIsActive]     = useState(true);
    const [sort, setSort]             = useState({ by: 'username', desc: false });

    const navigate = useNavigate();

    useEffect(() => {
        const params = new URLSearchParams({
            page,
            pageSize: size,
            sortBy:     sort.by,
            sortDesc:   sort.desc,
            activeOnly: isActive,
        });

        if (search) params.append('search', search);

        async function loadUsers() {
            try {
                const data = await api.get(`/users?${params.toString()}`);
                setUsers(data.content);
                setTotalPages(data.totalPages);
                setResponse(data);
                setLoadError("");
            } catch (err) {
                setUsers([]);
                setLoadError(problemMessage(err, "Uživatele se nepodařilo načíst."));
            }
        }

        const timer = setTimeout(loadUsers, 400);
        return () => clearTimeout(timer);
    }, [search, page, size, sort, isActive, refreshKey]);

    function handleSortChange(by, desc) {
        setSort({ by, desc });
        setPage(1);
    }

    function toggleUserStatus() {
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
                title="Uživatelé"
                actions={
                    <button className="btn btn-primary" onClick={() => navigate('/users/new')}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nový uživatel
                    </button>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="userSearch"
                    label="Hledat uživatele"
                    placeholder="Uživatelské jméno nebo email"
                    value={search}
                    onChange={(value) => { setSearch(value); setPage(1); }}
                    className="col-12 col-xl-8"
                />
                <ToggleFilter id="userActiveOnly" label="Jen aktivní"
                              checked={isActive} onChange={(value) => { setIsActive(value); setPage(1); }}
                              className="col-auto pb-2" />
            </ListToolbar>

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setRefreshKey(prev => prev + 1)} />
            ) : (
                <>
                    <UserTable
                        users={users}
                        toggleStatus={toggleUserStatus}
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
