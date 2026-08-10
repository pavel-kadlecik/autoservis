import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import * as React from "react";
import { api, problemMessage } from "../api/api.js";
import EmployeeTable from "../components/EmployeeTable.jsx";
import PageHeader from "../components/PageHeader.jsx";
import ListToolbar from "../components/filters/ListToolbar.jsx";
import SearchFilter from "../components/filters/SearchFilter.jsx";
import ToggleFilter from "../components/filters/ToggleFilter.jsx";
import { useAlert } from "../context/AlertContext.jsx";

/**
 * Správa zaměstnanců (ADMIN/MANAGER, D-7). Malý číselník — endpoint vrací prostý
 * seznam bez stránkování, hledání i řazení se dělají v prohlížeči (clientSort).
 */
export default function EmployeesPage() {

    const [search, setSearch] = useState("");
    const [employees, setEmployees] = useState([]);
    const [activeOnly, setActiveOnly] = useState(true);
    const [refreshKey, setRefreshKey] = useState(0);

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    useEffect(() => {
        let cancelled = false;
        async function loadEmployees() {
            try {
                const data = await api.get(`/employees?activeOnly=${activeOnly}`);
                if (!cancelled) setEmployees(data);
            } catch (err) {
                if (!cancelled) addAlert(problemMessage(err, "Zaměstnance se nepodařilo načíst."), "danger");
            }
        }
        loadEmployees();
        return () => { cancelled = true; };
    }, [activeOnly, refreshKey]);

    const filtered = useMemo(() => {
        const q = search.trim().toLowerCase();
        if (!q) return employees;
        return employees.filter(e =>
            (e.fullName ?? "").toLowerCase().includes(q)
            || (e.position ?? "").toLowerCase().includes(q));
    }, [employees, search]);

    function refresh() {
        setRefreshKey(prev => prev + 1);
    }

    return (
        <div>
            <PageHeader
                title="Zaměstnanci"
                actions={
                    <button className="btn btn-primary" onClick={() => navigate('/employees/new')}>
                        <i className="bi bi-plus-lg me-1" aria-hidden="true"></i>Nový zaměstnanec
                    </button>
                }
            />

            <ListToolbar>
                <SearchFilter
                    id="employeeSearch"
                    label="Hledat zaměstnance"
                    placeholder="Jméno nebo pozice"
                    value={search}
                    onChange={setSearch}
                    className="col-12 col-xl-8"
                />
                <ToggleFilter id="employeeActiveOnly" label="Jen aktivní"
                              checked={activeOnly} onChange={setActiveOnly} className="col-auto pb-2" />
            </ListToolbar>

            <EmployeeTable
                employees={filtered}
                toggleStatus={refresh}
                filtered={Boolean(search) || !activeOnly}
            />
        </div>
    );
}
