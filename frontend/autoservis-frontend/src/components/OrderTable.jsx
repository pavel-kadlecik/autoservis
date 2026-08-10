import * as React from 'react';
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import { orderActionItems } from "./orderActions.jsx";
import {
    formatDate,
    getEstimateDateColor,
    getOrderStatusTone,
    getOrderStatusLabel,
    getInvoiceStatusTone,
    getInvoiceStatusLabel, formatDateTime
} from "../api/format.js";

/**
 * Seznam zakázek nad sdílenou {@link DataTable}.
 *
 * Seřaditelné sloupce odpovídají whitelistu v `OrderMapper.xml` (viz `api.md`).
 * Stav se na serveru řadí podle pořadí ve workflow, ne abecedně.
 *
 * Zakázky nemají soft-delete (aktivace/deaktivace) — to je záležitost stavu
 * `CANCELLED`, ne příznaku `is_active` (viz tech-dluhy.md TD-67).
 *
 * Řádkové akce si tabulka **nedefinuje** — bere je z `orderActions.jsx`, aby nabízela
 * totéž co detail a editace. Dřív měla vlastní seznam a chybělo v něm mazání, zakázkový
 * list i fakturace.
 */
export default function OrderTable({orders, sort, onSortChange, filtered, onAction}) {

    const columns = [
        {
            key: "orderNumber", header: "Číslo zakázky", sortable: true,
            render: o => <span className="font-monospace text-muted small">{o.orderNumber}</span>,
        },
        {
            key: "status", header: "Stav", sortable: true,
            render: o => (
                <StatusBadge tone={getOrderStatusTone(o.status)}>
                    {getOrderStatusLabel(o.status)}
                </StatusBadge>
            ),
        },
        // Stav faktury zakázky (—/Koncept/Vystavena/Zaplacena). Neseřaditelné: server
        // pro invoiceStatus nemá řadicí klíč (jde o odvozenou projekci, ne sloupec orders).
        // „—" je prostý muted text, ne badge — nefakturováno není stav faktury.
        {
            key: "invoiceStatus", header: "Faktura",
            render: o => o.invoiceStatus
                ? (
                    <StatusBadge tone={getInvoiceStatusTone(o.invoiceStatus)}>
                        {getInvoiceStatusLabel(o.invoiceStatus)}
                    </StatusBadge>
                )
                : <span className="text-muted">—</span>,
        },
        {
            key: "customerName", header: "Zákazník", sortable: true,
            render: o => o.customerDisplayName ?? '—',
        },
        { key: "licensePlate", header: "SPZ", sortable: true, render: o => o.vehicleLicensePlate ?? '—' },
        { key: "brand", header: "Značka", sortable: true, render: o => o.vehicleBrand ?? '—' },
        // Model nemá vlastní klíč — bez značky nedává jeho pořadí smysl, proto je
        // na serveru součástí klíče `brand`.
        { key: "model", header: "Model", render: o => o.vehicleModel ?? '—' },
        {
            key: "estimatedCompletionAt", header: "Termín dokončení", sortable: true,
            render: o => (
                <span className={getEstimateDateColor(o.estimatedCompletionAt)}>
                    {formatDateTime(o.estimatedCompletionAt)}
                </span>
            ),
        },
        // Popis je volný text — řadit zakázky podle něj nedává provozní smysl.
        {
            // Ořez jako v OrderHistoryTable: bez něj roztáhl dlouhý popis sloupec přes celou
            // šířku a stlačil zbytek tabulky — číslo zakázky se pak lámalo na dva řádky.
            // CSS, ne substring: celý text zůstane v tooltipu i pro čtečku.
            key: "description", header: "Popis",
            render: o => o.description
                ? <span className="d-inline-block text-truncate" style={{ maxWidth: "22rem" }}
                        title={o.description}>{o.description}</span>
                : '—',
        },
    ];

    return (
        <div>
            <DataTable
                columns={columns}
                rows={orders}
                rowActions={o => orderActionItems(o, "list")}
                onAction={(action, o) => onAction?.(action, o)}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="clipboard2-check"
                        title={filtered ? "Filtru neodpovídá žádná zakázka." : "Zatím žádné zakázky."}
                        hint={filtered ? "Zkuste hledaný výraz zkrátit."
                                       : "Novou založíte tlačítkem nahoře."}
                    />
                }
            />
        </div>
    );
}
