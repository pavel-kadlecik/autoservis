import * as React from "react";
import { useNavigate } from "react-router-dom";
import {
    formatCurrency,
    formatDate,
    getInvoiceStatusLabel,
    getInvoiceStatusTone,
    getOrderStatusLabel,
    getOrderStatusTone,
} from "../api/format.js";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import LoadingState from "./LoadingState.jsx";

import VisibilityIcon from '@mui/icons-material/Visibility';

/**
 * Servisní historie — zakázky vozidla nebo zákazníka na jeho detailu (audit KN-27).
 * Vnořená v kartě, proto `dense`; řadí se **klientsky**, protože karta dostane hotovou
 * (serverem už seřazenou a omezenou) dávku, ne stránkovaný seznam.
 *
 * Sloupec „Faktura" čte `invoiceStatus` z `OrderDto.ListResponse` — stav **aktivní** faktury
 * zakázky, nebo „—" u nefakturované. Je to nejčastější otázka u pultu hned po tom, co se dělalo:
 * jestli je to zaplacené.
 *
 * Jediná akce je Detail — historie je ke čtení. Editace zakázky patří na její vlastní obrazovku.
 *
 * @param {Object[]} orders     - z GET /orders?vehicleId=… nebo ?customerId=… (null = načítá se)
 * @param {string}   emptyTitle - hláška prázdného stavu podle kontextu (vozidlo/zákazník)
 * @param {string}   [emptyHint]
 */
export default function OrderHistoryTable({ orders, emptyTitle, emptyHint }) {

    const navigate = useNavigate();

    if (!orders) return <LoadingState inline />;

    const columns = [
        {
            key: "orderNumber", header: "Číslo zakázky", sortable: true,
            render: o => <span className="font-monospace">{o.orderNumber}</span>,
        },
        // Stav jako 2. sloupec — sjednoceno napříč workflow tabulkami (zakázky/faktury/příjemky).
        {
            key: "status", header: "Stav", sortable: true,
            sortValue: o => getOrderStatusLabel(o.status),
            render: o => (
                <StatusBadge tone={getOrderStatusTone(o.status)}>
                    {getOrderStatusLabel(o.status)}
                </StatusBadge>
            ),
        },
        {
            key: "createdAt", header: "Přijato", sortable: true, className: "text-muted small",
            sortValue: o => o.createdAt,
            render: o => formatDate(o.createdAt),
        },
        {
            key: "description", header: "Popis",
            render: o => (
                <span className="d-inline-block text-truncate" style={{ maxWidth: "22rem" }}
                      title={o.description}>
                    {o.description}
                </span>
            ),
        },
        {
            // Finální cena je to, co zakázka nakonec stála; dokud není, ukazuje se odhad
            // (odlišený tlumeným „odhad", ať se nepletou dvě různá čísla).
            key: "price", header: "Cena", sortable: true, align: "end", className: "fw-medium",
            sortValue: o => Number(o.finalPrice ?? o.estimatedPrice ?? 0),
            render: o => (o.finalPrice != null
                ? formatCurrency(o.finalPrice)
                : (o.estimatedPrice != null
                    ? <span className="text-muted fw-normal">{formatCurrency(o.estimatedPrice)} <span className="small">(odhad)</span></span>
                    : '—')),
        },
        {
            key: "invoiceStatus", header: "Faktura", sortable: true,
            sortValue: o => getInvoiceStatusLabel(o.invoiceStatus),
            render: o => (o.invoiceStatus
                ? <StatusBadge tone={getInvoiceStatusTone(o.invoiceStatus)}>
                      {getInvoiceStatusLabel(o.invoiceStatus)}
                  </StatusBadge>
                : <span className="text-muted">—</span>),
        },
    ];

    return (
        <DataTable
            columns={columns}
            rows={orders}
            rowActions={() => [
                { id: "detail", label: "Detail", icon: <VisibilityIcon fontSize="small"/> },
            ]}
            onAction={(action, order) => {
                if (action === "detail") {
                    navigate(`/orders/${order.id}/detail`);
                }
            }}
            clientSort
            dense
            emptyState={<EmptyState icon="clipboard-check" title={emptyTitle} hint={emptyHint} />}
        />
    );
}
