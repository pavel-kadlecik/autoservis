import * as React from "react";
import ConfirmDialog from "./ConfirmDialog.jsx";
import DataTable from "./DataTable.jsx";
import StatusBadge from "./StatusBadge.jsx";
import EmptyState from "./EmptyState.jsx";
import VisibilityIcon from '@mui/icons-material/Visibility';
import EditIcon from '@mui/icons-material/Edit';
import BlockIcon from '@mui/icons-material/Block';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useWarehouseRowActions } from "../hooks/useWarehouseRowActions.js";
import { formatCurrency, formatQuantity, getActiveLabel, getActiveTone } from "../api/format.js";

/**
 * Přehled skladu nad sdílenou {@link DataTable}: jeden řádek = skladová karta.
 * Díly pod hlídaným minimem jsou zvýrazněné.
 *
 * @param {Object[]} products       - z GET /warehouse/products
 * @param {Function} toggleStatus   - refresh po (de)aktivaci
 * @param {Map}      valueByProduct - productId → hodnota zásoby (GET /warehouse/stock-valuation);
 *                                    neaktivní produkty ve view nejsou, sloupec u nich ukáže „—"
 * @param {Object}   [sort]         - {by, desc}
 * @param {Function} [onSortChange]
 * @param {boolean}  [filtered]
 */
export default function ProductTable({ products, toggleStatus, valueByProduct, sort, onSortChange, filtered }) {

    const {
        handleMenuAction, confirmAction,
        showConfirm, setShowConfirm,
        dialogTitle, dialogMessage,
    } = useWarehouseRowActions(toggleStatus);

    const columns = [
        {
            key: "sku", header: "SKU", sortable: true,
            render: p => <code className="small">{p.sku}</code>,
        },
        {
            key: "name", header: "Název dílu", sortable: true,
            render: p => {
                const subline = [p.manufacturer, p.variant].filter(Boolean).join(" · ");
                return (
                    <>
                        <div className="fw-medium">{p.name}</div>
                        {subline && <div className="text-muted small">{subline}</div>}
                    </>
                );
            },
        },
        { key: "unit", header: "MJ", sortable: true, className: "text-muted", render: p => p.unit },
        // Tři sloupce v pořadí, v jakém se to počítá: Skladem − Rezervováno = Dostupné
        // (rozhodnutí uživatele 2026-08-06; do té doby byla rezervace jen poznámkou pod
        // dostupným, takže nešla porovnat mezi řádky ani seřadit očima).
        // „Skladem" je fyzický stav — kolik kusů leží v regálu a co napočítá inventura.
        // „Dostupné" je to, co lze ještě naplánovat na další zakázku.
        {
            key: "quantityOnHand", header: "Skladem", sortable: true, align: "end",
            render: p => <span className="fw-semibold">{formatQuantity(p.quantityOnHand)}</span>,
        },
        {
            key: "quantityReserved", header: "Rezervováno", align: "end",
            className: "text-muted",
            // Pomlčka místo nuly: většina dílů rezervaci nemá a sloupec nul by tabulku
            // zašuměl natolik, že by v ní řádky s rezervací zanikly.
            render: p => Number(p.quantityReserved) > 0 ? (
                <span title="Díly slíbené otevřeným zakázkám — fyzicky leží na skladě, ale počítat se s nimi nedá">
                    {formatQuantity(p.quantityReserved)}
                </span>
            ) : <span className="text-body-tertiary">—</span>,
        },
        // Neřadí se — je to odvozený údaj, který není ve whitelistu řaditelných sloupců
        // (productSortOrder ve WarehouseMapper), stejně jako hodnota zásoby níž.
        {
            key: "quantityAvailable", header: "Dostupné", align: "end",
            render: p => (
                <>
                    <span className={`fw-semibold ${p.lowStock ? "text-danger" : ""}`}>
                        {formatQuantity(p.quantityAvailable)}
                    </span>
                    {p.lowStock && (
                        <StatusBadge tone="danger" className="ms-2">nízká zásoba</StatusBadge>
                    )}
                </>
            ),
        },
        // Hodnota zásoby se neřadí — nepočítá se v tomto dotazu, ale ve view
        // v_stock_valuation, které si FE páruje podle productId (viz WarehouseMapper).
        {
            key: "stockValue", header: "Nákupní cena bez DPH", align: "end",
            render: p => formatCurrency(valueByProduct?.get(p.id)),
        },
        {
            key: "salePrice", header: "Prodejní cena bez DPH", sortable: true, align: "end",
            className: "text-muted",
            render: p => formatCurrency(p.salePrice),
        },
        // Stav je filtr („Jen aktivní"), ne řadicí kritérium.
        {
            key: "active", header: "Stav",
            render: p => <StatusBadge tone={getActiveTone(p.active)}>{getActiveLabel(p.active)}</StatusBadge>,
        },
    ];

    function rowActions(p) {
        return [
            {id: "detail", label: "Detail",   icon: <VisibilityIcon fontSize="small"/>},
            {id: "edit",   label: "Editovat", icon: <EditIcon fontSize="small"/>},
            ...(p.active
                ? [{id: "deactivate", label: "Deaktivovat", icon: <BlockIcon fontSize="small"/>,      color: "error.main"}]
                : [{id: "activate",   label: "Aktivovat",   icon: <CheckCircleIcon fontSize="small"/>, color: "success.main"}]
            ),
        ];
    }

    return (
        <div>
            <DataTable
                columns={columns}
                rows={products ?? []}
                rowActions={rowActions}
                onAction={handleMenuAction}
                sort={sort}
                onSortChange={onSortChange}
                emptyState={
                    <EmptyState
                        icon="boxes"
                        title={filtered ? "Filtru neodpovídá žádná skladová položka."
                                        : "Sklad je zatím prázdný."}
                        hint={filtered ? "Zkuste hledaný výraz zkrátit nebo vypnout filtry."
                                       : "Kartu založíte tlačítkem nahoře, nebo vznikne importem dokladu."}
                    />
                }
            />

            <ConfirmDialog
                title={dialogTitle}
                message={dialogMessage}
                show={showConfirm}
                onConfirm={() => confirmAction()}
                onCancel={() => setShowConfirm(false)}
            />
        </div>
    );
}
