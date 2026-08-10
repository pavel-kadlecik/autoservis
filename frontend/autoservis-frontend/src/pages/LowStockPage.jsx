import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import { formatCurrency, formatDate, formatQuantity } from "../api/format.js";
import LoadingState from "../components/LoadingState.jsx";
import LoadErrorState from "../components/LoadErrorState.jsx";
import PageHeader from "../components/PageHeader.jsx";
import EmptyState from "../components/EmptyState.jsx";
import DataTable from "../components/DataTable.jsx";

/**
 * Přehled dílů pod hlídaným minimem i s doporučeným dodavatelem (E8.3, P-7).
 *
 * Doporučení pochází z převodníku supplier_products — od dodavatele, který díl
 * dodal naposledy. Je to podklad pro objednání, ne objednávka: aplikace
 * objednávkový modul vědomě nemá.
 *
 * Endpoint vrací celé pole (bez stránkování), takže se řadí **na klientovi**
 * (`clientSort`).
 */
export default function LowStockPage() {

    const navigate = useNavigate();
    const [rows, setRows] = useState(null);
    // Bez ošetření chyby se stránka zasekla na spinneru „Načítám…" navždy (KN-14).
    const [loadError, setLoadError] = useState("");
    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        async function load() {
            try {
                setRows(await api.get("/warehouse/products/low-stock"));
                setLoadError("");
            } catch (err) {
                setRows([]);
                setLoadError(problemMessage(err, "Přehled dílů pod minimem se nepodařilo načíst."));
            }
        }
        load();
    }, [reloadKey]);

    if (rows === null) return <LoadingState />;

    const columns = [
        {
            key: "sku", header: "SKU", sortable: true,
            sortValue: r => r.sku,
            render: r => <code className="small">{r.sku}</code>,
        },
        { key: "name", header: "Název dílu", sortable: true, sortValue: r => r.name, render: r => r.name },
        {
            key: "quantityOnHand", header: "Skladem", sortable: true, align: "end",
            sortValue: r => Number(r.quantityOnHand),
            className: "text-muted",
            render: r => formatQuantity(r.quantityOnHand, r.unit),
        },
        {
            key: "quantityReserved", header: "Rezervováno", sortable: true, align: "end",
            sortValue: r => Number(r.quantityReserved),
            className: "text-muted",
            // Pomlčka místo nuly — viz ProductTable: sloupec nul by řádky s rezervací utopil.
            render: r => Number(r.quantityReserved) > 0
                ? formatQuantity(r.quantityReserved)
                : <span className="text-body-tertiary">—</span>,
        },
        {
            // „Chybí" se počítá z DOSTUPNÉHO, ne z fyzického stavu — díl slíbený jiné zakázce
            // je pro další práci nedostupný. Bez tohohle sloupce ukazovala tabulka aritmetiku,
            // která na obrazovce nevycházela: skladem 2, minimum 5, chybí 4. Rezervace
            // v odpovědi byla, jen se nevykreslovala.
            key: "quantityAvailable", header: "Dostupné", sortable: true, align: "end",
            sortValue: r => Number(r.quantityAvailable),
            className: "text-danger fw-semibold",
            render: r => formatQuantity(r.quantityAvailable, r.unit),
        },
        {
            key: "minStockLevel", header: "Minimum", sortable: true, align: "end",
            sortValue: r => Number(r.minStockLevel),
            className: "text-muted",
            render: r => formatQuantity(r.minStockLevel),
        },
        {
            key: "missingQuantity", header: "Chybí", sortable: true, align: "end",
            sortValue: r => Number(r.missingQuantity),
            className: "fw-semibold",
            render: r => formatQuantity(r.missingQuantity),
        },
        {
            key: "supplierName", header: "Doporučený dodavatel", sortable: true,
            sortValue: r => r.supplierName,
            render: r => r.supplierName ? (
                <>
                    <div>{r.supplierName}</div>
                    <div className="text-muted small">
                        kód {r.supplierSku}
                        {r.lastSeenAt && <> · naposledy {formatDate(r.lastSeenAt)}</>}
                    </div>
                </>
            ) : (
                <span className="text-muted fst-italic small">zatím nedodával žádný dodavatel</span>
            ),
        },
        {
            key: "lastUnitPriceExclVat", header: "Poslední cena bez DPH", sortable: true, align: "end",
            sortValue: r => (r.lastUnitPriceExclVat == null ? null : Number(r.lastUnitPriceExclVat)),
            render: r => formatCurrency(r.lastUnitPriceExclVat),
        },
    ];

    return (
        <div>
            <PageHeader
                title="Pod minimem"
                subtitle="Díly, kterých je na skladě méně, než kolik má hlídané minimum."
            />

            {loadError ? (
                <LoadErrorState message={loadError} onRetry={() => setReloadKey(prev => prev + 1)} />
            ) : (
                <>
                    {rows.length > 0 && (
                        <p className="text-muted small">
                            Dodavatel je ten, který díl dodal naposledy — včetně jeho
                            katalogového čísla, se kterým se objednává.
                        </p>
                    )}

                    <DataTable
                        columns={columns}
                        rows={rows}
                        rowKey={r => r.productId}
                        clientSort
                        onRowClick={r => navigate(`/warehouse/${r.productId}/detail`)}
                        emptyState={
                            <EmptyState icon="check-circle"
                                        title="Všechny hlídané díly jsou nad minimem."
                                        hint="Hlídá se jen u dílů, které mají vyplněný minimální stav." />
                        }
                    />
                </>
            )}
        </div>
    );
}
