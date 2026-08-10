import * as React from "react";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import {
    formatCurrency,
    formatDate, formatDateTime,
    getDocumentTypeLabel,
    getReceiptStatusLabel,
    getReceiptStatusTone,
} from "../api/format.js";

/**
 * Seznam příjemek nad sdílenou {@link DataTable}. Vytaženo ze stránky, kde byla
 * tabulka psaná přímo v JSX (U3.3).
 *
 * Řádek je klikatelný — příjemka nemá jiné akce než „otevřít kontrolní obrazovku",
 * takže sloupec s třitečkovým menu by nesl jedinou položku.
 *
 * @param {Object[]} receipts
 * @param {Function} onOpen(receipt)
 * @param {Object}   [sort]
 * @param {Function} [onSortChange]
 * @param {boolean}  [filtered]
 */
export default function ReceiptTable({ receipts, onOpen, sort, onSortChange, filtered }) {

    const columns = [
        {
            key: "documentNumber", header: "Číslo dokladu", sortable: true,
            render: r => r.documentNumber ?? "—",
        },
        // Stav jako 2. sloupec — sjednoceno napříč workflow tabulkami (zakázky/faktury/
        // příjemky mají „Stav" hned za identifikátorem).
        {
            key: "status", header: "Stav", sortable: true,
            render: r => (
                <StatusBadge tone={getReceiptStatusTone(r.status)}>
                    {getReceiptStatusLabel(r.status)}
                </StatusBadge>
            ),
        },
        {
            key: "documentType", header: "Typ", sortable: true,
            render: r => getDocumentTypeLabel(r.documentType),
        },
        {
            key: "supplierName", header: "Dodavatel", sortable: true,
            render: r => r.supplierName ?? "—",
        },
        {
            key: "issueDate", header: "Vystaveno", sortable: true,
            render: r => (r.issueDate ? formatDate(r.issueDate) : "—"),
        },
        {
            key: "totalAmount", header: "Celkem s DPH", sortable: true, align: "end",
            render: r => formatCurrency(r.totalAmount),
        },
        // Kontroly se neřadí — reconciliationOk je odvozený příznak z výsledků
        // kontrol draftu, ne sloupec v tabulce (viz ReceiptReviewMapper).
        {
            key: "reconciliationOk", header: "Kontroly",
            render: r => r.reconciliationOk
                ? <i className="bi bi-check-circle-fill text-success" title="Kontrolní součty sedí" />
                : <i className="bi bi-exclamation-triangle-fill text-warning" title="Kontrolní součty nesedí" />,
        },
        {
            key: "createdAt", header: "Vytvořeno", sortable: true,
            render: r => formatDateTime(r.createdAt),
        },
    ];

    return (
        <DataTable
            columns={columns}
            rows={receipts}
            onRowClick={onOpen}
            sort={sort}
            onSortChange={onSortChange}
            emptyState={
                <EmptyState
                    icon="file-earmark-arrow-down"
                    title={filtered ? "Filtru neodpovídá žádná příjemka." : "Zatím žádné příjemky."}
                    hint={filtered ? "Zkuste hledaný výraz zkrátit nebo změnit filtry."
                                   : "Doklad nahrajete tlačítkem nahoře, nebo příjemku založíte ručně."}
                />
            }
        />
    );
}
