import * as React from "react";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import {
    formatCurrency,
    formatDate, formatDateTime,
    formatQuantity,
    getActiveLabel,
    getActiveTone,
    getMovementTypeLabel,
    getOrderStatusLabel,
    getOrderStatusTone,
    getReturnReasonLabel,
} from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import PageHeader from "../components/PageHeader.jsx";
import EntityAvatar from "../components/EntityAvatar.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import ConfirmDialog from "../components/ConfirmDialog.jsx";
import StockMovementModal from "../components/StockMovementModal.jsx";
import LoadingState from "../components/LoadingState.jsx";
import MetricCard from "../components/MetricCard.jsx";
import MetricRow from "../components/MetricRow.jsx";
import DetailCard from "../components/DetailCard.jsx";
import DataTable from "../components/DataTable.jsx";
import EmptyState from "../components/EmptyState.jsx";
import ErrorState from "../components/ErrorState.jsx";

export default function WarehousePageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const [product, setProduct] = useState(null);
    // Bez ošetření chyby zůstal detail navždy na spinneru „Načítám…" (KN-14) —
    // třeba u zastaralého odkazu na smazaný záznam (404).
    const [loadError, setLoadError] = useState("");
    const [showConfirm, setShowConfirm] = useState(false);
    const [showMovement, setShowMovement] = useState(false);

    useEffect(() => {
        async function load() {
            try {
                const data = await api.get(`/warehouse/products/${id}`);
                setProduct(data);
                setLoadError("");
            } catch (err) {
                setLoadError(problemMessage(err, "Skladovou kartu se nepodařilo načíst."));
            }
        }
        load();
    }, [id]);

    async function handleToggleStatus() {
        try {
            const updated = product.active
                ? await api.delete(`/warehouse/products/${id}`)
                : await api.post(`/warehouse/products/${id}/activate`);
            setProduct(updated);
        } catch (e) {
            addAlert(problemMessage(e, "Akci se nepodařilo provést."), "danger");
        } finally {
            setShowConfirm(false);
        }
    }

    if (!product && !loadError) return <LoadingState />;
    if (!product) {
        return <ErrorState message={loadError} backTo="/warehouse" backLabel="Zpět na sklad" />;
    }

    const batches = product.batches ?? [];
    const movements = product.movements ?? [];
    const reservations = product.reservations ?? [];

    // Kdo díl drží rezervovaný. Číslo zakázky je proklik — obsluha se odtud dostane
    // rovnou k tomu, kdo si díl slíbil, a může se domluvit na přerovnání.
    const reservationColumns = [
        {
            key: "orderNumber", header: "Zakázka", sortable: true,
            sortValue: r => r.orderNumber,
            render: r => <a href={`/orders/${r.orderId}/detail`}>{r.orderNumber}</a>,
        },
        {
            key: "customerName", header: "Zákazník", sortable: true,
            sortValue: r => r.customerName, render: r => r.customerName || "—",
        },
        {
            key: "orderStatus", header: "Stav zakázky", sortable: true,
            sortValue: r => getOrderStatusLabel(r.orderStatus),
            render: r => (
                <StatusBadge tone={getOrderStatusTone(r.orderStatus)}>
                    {getOrderStatusLabel(r.orderStatus)}
                </StatusBadge>
            ),
        },
        {
            key: "quantity", header: "Množství", sortable: true, align: "end",
            sortValue: r => Number(r.quantity),
            render: r => `${formatQuantity(r.quantity)} ${product.unit}`,
        },
        {
            key: "reservedAt", header: "Rezervováno", sortable: true,
            sortValue: r => r.reservedAt, render: r => formatDate(r.reservedAt),
        },
    ];

    const batchColumns = [
        {
            key: "issueDate", header: "Datum příjmu", sortable: true,
            sortValue: b => b.issueDate, render: b => formatDate(b.issueDate),
        },
        { key: "invoiceNumber", header: "Faktura", sortable: true,
          sortValue: b => b.invoiceNumber, render: b => b.invoiceNumber || "—" },
        { key: "orderNumber", header: "Objednávka", sortable: true,
          sortValue: b => b.orderNumber, render: b => b.orderNumber || "—" },
        { key: "supplierName", header: "Dodavatel", sortable: true,
          sortValue: b => b.supplierName, render: b => b.supplierName || "—" },
        {
            key: "quantityReceived", header: "Přijato", sortable: true, align: "end",
            sortValue: b => Number(b.quantityReceived), render: b => formatQuantity(b.quantityReceived),
        },
        {
            key: "quantityRemaining", header: "Zbývá", sortable: true, align: "end",
            sortValue: b => Number(b.quantityRemaining), className: "fw-semibold",
            render: b => formatQuantity(b.quantityRemaining),
        },
        {
            key: "unitPriceExclVat", header: "Nákupní cena bez DPH", sortable: true, align: "end",
            sortValue: b => (b.unitPriceExclVat == null ? null : Number(b.unitPriceExclVat)),
            className: "text-muted", render: b => formatCurrency(b.unitPriceExclVat),
        },
    ];

    const movementColumns = [
        {
            key: "movedAt", header: "Datum a čas", sortable: true,
            sortValue: m => m.movedAt, render: m => formatDateTime(m.movedAt),
        },
        {
            key: "movementType", header: "Typ", sortable: true,
            sortValue: m => getMovementTypeLabel(m.movementType),
            render: m => getMovementTypeLabel(m.movementType),
        },
        {
            // Znaménko nese informaci samo (příjem +, výdej −), barva ji jen zdůrazňuje.
            key: "quantity", header: "Množství", sortable: true, align: "end",
            sortValue: m => Number(m.quantity),
            render: m => {
                const qty = Number(m.quantity);
                const tone = qty > 0 ? "text-success" : qty < 0 ? "text-danger" : "";
                return (
                    <span className={`fw-semibold ${tone}`}>
                        {qty > 0 ? "+" : ""}{formatQuantity(m.quantity)}
                    </span>
                );
            },
        },
        {
            key: "orderNumber", header: "Zakázka / doklad", sortable: true,
            sortValue: m => m.orderNumber || m.creditNoteNumber,
            // Zakázku lze od V87 smazat a pohyb v append-only ledgeru zůstane — číslo pak
            // chybí, ale `orderId` zůstává. Prostá pomlčka by tvrdila, že pohyb se zakázkou
            // nesouvisel, což není pravda: materiál na ni opravdu odešel a vrátil se.
            render: m => m.orderNumber
                || m.creditNoteNumber
                || (m.orderId
                    ? <span className="fst-italic" title="Zakázka byla smazána">smazaná zakázka</span>
                    : "—"),
        },
        {
            key: "note", header: "Poznámka", className: "text-muted small",
            render: m => (
                <>
                    {m.returnReason && (
                        <StatusBadge tone="warning" className="me-1">
                            {getReturnReasonLabel(m.returnReason)}
                        </StatusBadge>
                    )}
                    {m.note || (m.returnReason ? "" : "—")}
                </>
            ),
        },
    ];

    // Koncová cena pro zákazníka = prodejní cena bez DPH + sazba dílu.
    const saleGross = (product.salePrice != null && product.defaultVatRate != null)
        ? Number(product.salePrice) * (1 + Number(product.defaultVatRate) / 100)
        : null;

    return (
        <div>
            <PageHeader
                title={product.name}
                subtitle={product.sku}
                backTo="/warehouse"
                avatar={<EntityAvatar name={product.name} />}
                badges={
                    <StatusBadge tone={getActiveTone(product.active)}>
                        {getActiveLabel(product.active)}
                    </StatusBadge>
                }
                actions={
                    <>
                        <button className="btn btn-outline-secondary"
                                onClick={() => navigate(`/warehouse/${id}/edit`)}>
                            <i className="bi bi-pencil me-1" aria-hidden="true"></i>Editovat
                        </button>
                        <button className="btn btn-primary" onClick={() => setShowMovement(true)}>
                            <i className="bi bi-arrow-down-up me-1" aria-hidden="true"></i>Skladový pohyb
                        </button>
                        <button className={product.active ? 'btn btn-outline-danger' : 'btn btn-outline-success'}
                                onClick={() => setShowConfirm(true)}>
                            <i className={`bi bi-${product.active ? 'slash-circle' : 'check-circle'} me-1`}
                               aria-hidden="true"></i>
                            {product.active ? 'Deaktivovat' : 'Aktivovat'}
                        </button>
                    </>
                }
            />

            <MetricRow>
                {/* Fyzický stav a dostupné vedle sebe: „Skladem" je to, co napočítá
                    inventura, „Dostupné" to, co lze ještě naplánovat na další zakázku.
                    Odznak nízké zásoby patří k dostupnému — proti němu se minimum hlídá. */}
                <MetricCard label="Skladem" value={formatQuantity(product.quantityOnHand)} unit={product.unit} />
                <MetricCard label="Dostupné" value={
                    <>
                        {formatQuantity(product.quantityAvailable)}
                        {Number(product.quantityReserved) > 0 && (
                            <span className="d-block small fw-normal text-muted">
                                rezervováno {formatQuantity(product.quantityReserved)} {product.unit}
                            </span>
                        )}
                    </>
                } unit={product.unit}
                            tone={product.lowStock ? "danger" : undefined} />
                <MetricCard label="Prodejní cena (bez DPH)" value={
                    product.salePrice == null ? null : (
                        <>
                            {formatCurrency(product.salePrice)}
                            {saleGross != null && (
                                <span className="d-block small fw-normal text-muted">
                                    {formatCurrency(saleGross)} s DPH
                                </span>
                            )}
                        </>
                    )
                } />
                <MetricCard label="Šarží na skladě" value={batches.length} />
                <MetricCard label="Pohybů" value={movements.length} />
            </MetricRow>

            {product.lowStock && (
                <div className="alert alert-warning py-2 small">
                    <i className="bi bi-exclamation-triangle me-1"></i>
                    Zásoba pod hlídaným minimem ({formatQuantity(product.minStockLevel)} {product.unit}).
                </div>
            )}

            <DetailCard title="Identifikace a parametry">
                <dl className="row mb-0">
                    <dt className="col-sm-3 text-muted fw-normal">Výrobce</dt>
                    <dd className="col-sm-9">{product.manufacturer || '—'}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Varianta / aplikace</dt>
                    <dd className="col-sm-9">{product.variant || '—'}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Měrná jednotka</dt>
                    <dd className="col-sm-9">{product.unit}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">DPH</dt>
                    <dd className="col-sm-9">{product.defaultVatRate != null ? `${product.defaultVatRate} %` : '—'}</dd>

                    <dt className="col-sm-3 text-muted fw-normal">Min. stav (hlídání)</dt>
                    <dd className="col-sm-9">
                        {product.minStockLevel != null
                            ? `${formatQuantity(product.minStockLevel)} ${product.unit}`
                            : 'nehlídá se'}
                    </dd>

                    <dt className="col-sm-3 text-muted fw-normal">Poznámka</dt>
                    <dd className="col-sm-9">{product.note || '—'}</dd>
                </dl>
            </DetailCard>

            {/* Rozpad rezervací — vysvětluje, proč je dostupné množství nižší než fyzický
                stav. Karta se zobrazí jen když díl někdo drží; u nerezervovaného dílu by
                prázdná tabulka jen zabírala místo. */}
            {reservations.length > 0 && (
                <DetailCard title="Rezervováno na zakázkách">
                    <p className="small text-muted mb-2">
                        Tyhle zakázky si díl naplánovaly, ale ze skladu ještě neodešel — fyzicky
                        leží v regálu, počítat se s ním ale nedá.
                    </p>
                    <DataTable
                        columns={reservationColumns}
                        rows={reservations}
                        rowKey={r => r.orderId}
                        clientSort
                        dense
                    />
                </DetailCard>
            )}

            <DetailCard title="Šarže na skladě">
                <DataTable
                    columns={batchColumns}
                    rows={batches}
                    rowKey={b => b.batchId}
                    clientSort
                    dense
                    emptyState={
                        <EmptyState icon="box-seam" title="Žádné šarže."
                                    hint="Díl zatím nebyl naskladněn — šarže vzniká příjemkou." />
                    }
                />
            </DetailCard>

            <DetailCard title="Pohyby">
                <DataTable
                    columns={movementColumns}
                    rows={movements}
                    clientSort
                    dense
                    emptyState={
                        <EmptyState icon="arrow-down-up" title="Žádné skladové pohyby."
                                    hint="Pohyb vzniká naskladněním, výdejem do zakázky nebo ručním pohybem." />
                    }
                />
            </DetailCard>

            <StockMovementModal
                show={showMovement}
                product={product}
                onClose={() => setShowMovement(false)}
                onSaved={(updated) => {
                    setProduct(updated);   // stav, šarže i historie přijdou z odpovědi
                    addAlert("Skladový pohyb byl zaznamenán.", "success");
                }}
            />

            <ConfirmDialog
                title={product.active ? 'Potvrďte deaktivaci položky' : 'Potvrďte aktivaci položky'}
                message={product.active
                    ? `Opravdu chcete deaktivovat položku ${product.name} (${product.sku})?`
                    : `Opravdu chcete aktivovat položku ${product.name} (${product.sku})?`}
                show={showConfirm}
                onConfirm={handleToggleStatus}
                onCancel={() => setShowConfirm(false)}
            />
        </div>
    );
}

