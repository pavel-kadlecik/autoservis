import * as React from 'react';
import DataTable from "./DataTable.jsx";
import { invoiceActionItems } from "./invoiceActions.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import {
    formatDate,
    formatCurrency,
    getInvoiceStatusLabel,
    getInvoiceStates,
    getInvoiceStatusTone,
} from "../api/format.js";


/**
 * Seznam faktur nad sdílenou {@link DataTable}.
 *
 * Stavové přechody se dřív nabízely jako `btn-group` textových tlačítek přímo v řádku —
 * jediná tabulka v aplikaci, která to tak měla. Teď jsou v řádkovém menu jako u ostatních
 * seznamů (U3.4).
 *
 * Tabulka si je ale **nedefinuje ani neprovádí**: CO se nabízí říká `invoiceActionItems`,
 * CO to udělá `useInvoiceActions` na stránce. Menu tím nabízí přesně krok vpřed a krok zpět
 * podle stavu — dřív měla vlastní čtyři akce, které se stavem nehýbaly úplně (chyběly
 * předání, návrat do konceptu i obě vzetí zpět).
 *
 * @param {Array}    invoices
 * @param {Function} onAction - (actionId, invoice); přechody řeší `useInvoiceActions`
 * @param {Object}   [sort]
 * @param {Function} [onSortChange]
 * @param {boolean}  [filtered]
 */
export default function InvoiceTable({ invoices, onAction, sort, onSortChange, filtered }) {

    const columns = [
        {
            key: "invoiceNumber", header: "Číslo faktury", sortable: true,
            // Fallback pro historické koncepty bez čísla (před V71) — sjednoceno
            // s CustomerInvoicesTable, prázdná buňka vypadala jako chyba renderu.
            render: inv => inv.invoiceNumber
                ? <span className="font-monospace">{inv.invoiceNumber}</span>
                : <span className="text-muted">—</span>,
        },
        // Stav jako 2. sloupec — sjednoceno napříč workflow tabulkami (zakázky/faktury/
        // příjemky mají „Stav" hned za identifikátorem).
        {
            // Dobropis stav faktury nemění (zůstává Vystavena/Zaplacena) — bez druhého
            // odznaku nešlo v přehledu poznat, které doklady jsou opravené. Stav zůstává
            // první: říká, co je doklad zač; dobropis je doplňující informace.
            key: "status", header: "Stav", sortable: true,
            // Odznaky pod sebe, ne vedle sebe (rozhodnutí uživatele 2026-08-08): dva vedle
            // sebe roztahovaly sloupec a na užším displeji se stejně zalamovaly nepravidelně.
            render: inv => (
                <div className="d-flex flex-column align-items-start gap-1">
                    {getInvoiceStates(inv).map(state => (
                        <StatusBadge key={state.label} tone={state.tone}>{state.label}</StatusBadge>
                    ))}
                </div>
            ),
        },
        {
            key: "customerName", header: "Zákazník", sortable: true, className: "fw-medium",
            render: inv => inv.customerDisplayName,
        },
        // Zakázka je snapshot čísla na faktuře — řadí se podle ní jen výjimečně,
        // server pro ni whitelist nemá.
        {
            key: "orderNumber", header: "Zakázka",
            // Proklik na zakázku, ze které faktura vznikla. Zakázku s fakturou nelze smazat
            // (ORDER_HAS_INVOICE_CANNOT_DELETE), takže odkaz nemůže vést do prázdna.
            render: inv => inv.orderId
                ? <a href={`/orders/${inv.orderId}/detail`}
                     className="font-monospace small">{inv.orderNumber ?? '—'}</a>
                : <span className="font-monospace text-muted small">{inv.orderNumber ?? '—'}</span>,
        },
        {
            // Popis zakázky — v seznamu jde o orientaci „co to bylo za práci"; číslo zakázky
            // samo o sobě nic neřekne. Bere se živě ze zakázky, snímek to není (2026-08-08).
            // Ořez CSS, ne substringem: celý text zůstane v tooltipu i pro čtečku.
            key: "orderDescription", header: "Popis", sortable: true,
            className: "text-muted small",
            render: inv => inv.orderDescription
                ? <span className="d-inline-block text-truncate" style={{ maxWidth: "16rem" }}
                        title={inv.orderDescription}>{inv.orderDescription}</span>
                : '—',
        },
        {
            key: "issueDate", header: "Vystaveno", sortable: true, className: "text-muted small",
            render: inv => formatDate(inv.issueDate),
        },
        {
            key: "dueDate", header: "Splatnost", sortable: true, className: "text-muted small",
            render: inv => formatDate(inv.dueDate),
        },
        {
            // Zobrazuje se částka K ÚHRADĚ (u hotovosti zaokrouhlená na celé Kč, V67/KN-7),
            // aby sloupec seděl s dokladem. Klíč řazení zůstal `totalGross` kvůli API,
            // ale server podle téhle hodnoty i řadí.
            key: "totalGross", header: "Celkem k úhradě", sortable: true, align: "end", className: "fw-medium",
            render: inv => formatCurrency(inv.totalToPay ?? inv.totalGross),
        },
    ];

    function rowActions(inv) {
        const busy = busyId === inv.id;
        return [
            { id: "detail", label: "Detail", icon: <VisibilityIcon fontSize="small"/> },
            // Vystavení otevře dialog s číslem a VS — přepínač ve Fakturačních údajích
            // řídí jen to, jestli je číslo předvyplněné podle masky, nebo prázdné.
            ...(inv.status === 'DRAFT' && !busy
                ? [{ id: "issue", label: "Vystavit", icon: <SendIcon fontSize="small"/> }] : []),
            ...(inv.status === 'ISSUED' && !busy
                ? [{ id: "pay", label: "Označit zaplaceno", icon: <PaidIcon fontSize="small"/> }] : []),
            // Mazání jen u konceptu — vystavený doklad se opravuje dobropisem (§42/§45, audit KN-1).
            ...(inv.status === 'DRAFT' && !busy
                ? [{ id: "delete", label: "Smazat", icon: <DeleteIcon fontSize="small"/>, color: "error.main" }] : []),
        ];
    }

    return (
        <>
            <DataTable
                columns={columns}
                rows={invoices}
                rowActions={inv => invoiceActionItems(inv, "list")}
                onAction={onAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="receipt"
                        title={filtered ? "Filtru neodpovídá žádná faktura." : "Zatím žádné faktury."}
                        hint="Faktura vzniká ze zakázky — otevřete zakázku a použijte „Vytvořit fakturu“."
                    />
                }
            />

        </>
    );
}
