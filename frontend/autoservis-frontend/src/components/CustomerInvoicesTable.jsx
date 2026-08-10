import * as React from "react";
import { useNavigate } from "react-router-dom";
import {
    formatCurrency,
    formatDate,
    getInvoiceStatusLabel,
    getInvoiceStates,
    getInvoiceStatusTone,
} from "../api/format.js";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import LoadingState from "./LoadingState.jsx";

import VisibilityIcon from '@mui/icons-material/Visibility';

/**
 * Faktury zákazníka na jeho detailu (audit KN-27 / P-5). Vnořená v kartě, proto `dense`;
 * řadí se klientsky, protože endpoint `GET /invoices/customer/{id}` vrací celý seznam najednou.
 *
 * Read-only záměrně: stavové přechody faktury (vystavit, zaplaceno, storno) zůstávají na seznamu
 * faktur a na detailu dokladu — tady jde o odpověď na „co u nás nechal", ne o práci s dokladem.
 * Proto ani žádné řádkové akce kromě Detailu.
 *
 * Zobrazuje se **částka k úhradě** (`totalToPay`, u hotovosti zaokrouhlená na celé Kč, V67),
 * aby sloupec seděl s dokladem; `totalGross` je fallback pro starší odpovědi.
 *
 * @param {Object[]} invoices - z GET /invoices/customer/{id} (null = načítá se)
 */
export default function CustomerInvoicesTable({ invoices }) {

    const navigate = useNavigate();

    if (!invoices) return <LoadingState inline />;

    const columns = [
        {
            key: "invoiceNumber", header: "Číslo faktury", sortable: true,
            render: inv => (
                <span className="font-monospace">
                    {inv.invoiceNumber ?? <span className="text-muted">—</span>}
                </span>
            ),
        },
        {
            key: "status", header: "Stav", sortable: true,
            sortValue: inv => getInvoiceStates(inv)[0]?.label,
            // Sjednoceno s hlavním seznamem faktur — tytéž odznaky ze sdílené funkce.
            render: inv => (
                <div className="d-flex flex-column align-items-start gap-1">
                    {getInvoiceStates(inv).map(state => (
                        <StatusBadge key={state.label} tone={state.tone}>{state.label}</StatusBadge>
                    ))}
                </div>
            ),
        },
        {
            key: "orderNumber", header: "Zakázka",
            render: inv => <span className="font-monospace text-muted small">{inv.orderNumber ?? '—'}</span>,
        },
        {
            key: "issueDate", header: "Vystaveno", sortable: true, className: "text-muted small",
            sortValue: inv => inv.issueDate,
            render: inv => formatDate(inv.issueDate),
        },
        {
            key: "dueDate", header: "Splatnost", sortable: true, className: "text-muted small",
            sortValue: inv => inv.dueDate,
            render: inv => formatDate(inv.dueDate),
        },
        {
            key: "totalToPay", header: "Celkem k úhradě", sortable: true, align: "end", className: "fw-medium",
            sortValue: inv => Number(inv.totalToPay ?? inv.totalGross ?? 0),
            render: inv => formatCurrency(inv.totalToPay ?? inv.totalGross),
        },
    ];

    return (
        <DataTable
            columns={columns}
            rows={invoices}
            rowActions={() => [
                { id: "detail", label: "Detail", icon: <VisibilityIcon fontSize="small"/> },
            ]}
            onAction={(action, invoice) => {
                if (action === "detail") {
                    navigate(`/invoices/${invoice.id}/detail`);
                }
            }}
            clientSort
            dense
            emptyState={
                <EmptyState icon="receipt"
                            title="Zákazník nemá žádnou fakturu."
                            hint="Faktura vzniká ze zakázky — otevřete zakázku a použijte „Vytvořit fakturu“." />
            }
        />
    );
}
