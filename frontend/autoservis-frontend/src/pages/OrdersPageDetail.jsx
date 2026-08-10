import * as React from 'react';
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, problemMessage } from "../api/api.js";
import {
    formatCurrency,
    formatDate,
    formatNumber,
    getEstimateDateColor,
    getOrderItemTypeLabel,
    getOrderStatusTone,
    getOrderStatusLabel,
    withVat,
} from "../api/format.js";
import StatusBadge from "../components/StatusBadge.jsx";
import OrderItemStock from "../components/OrderItemStock.jsx";
import OrderItemName from "../components/OrderItemName.jsx";
import PageHeader from "../components/PageHeader.jsx";
import EntityAvatar from "../components/EntityAvatar.jsx";
import { useOrderActions, OrderActionButtons } from "../components/orderActions.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import LoadingState from "../components/LoadingState.jsx";
import MetricCard from "../components/MetricCard.jsx";
import MetricRow from "../components/MetricRow.jsx";
import DetailCard from "../components/DetailCard.jsx";
import DataTable from "../components/DataTable.jsx";
import EmptyState from "../components/EmptyState.jsx";

export default function OrdersPageDetail() {

    const { id } = useParams();
    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const [order, setOrder] = useState(null);
    const [items, setItems] = useState([]);
    const [appointment, setAppointment] = useState(null);
    const [reloadKey, setReloadKey] = useState(0);

    // Akce nad zakázkou bere detail ze sdíleného hooku — nabízí tutéž sadu jako seznam
    // a editace a nesmí se s nimi rozejít (viz orderActions.jsx).
    const { run: runOrderAction, dialogs: orderDialogs, busy } = useOrderActions({
        onChanged: updated => updated ? setOrder(updated) : setReloadKey(k => k + 1),
        onDeleted: () => navigate("/orders"),
    });

    useEffect(() => {
        async function loadOrder() {
            try {
                const [orderData, itemsData] = await Promise.all([
                    api.get(`/orders/${id}`),
                    api.get(`/orders/${id}/items`),
                ]);
                setOrder(orderData);
                setItems(itemsData ?? []);
            } catch (error) {
                addAlert(problemMessage(error, "Zakázku se nepodařilo načíst."), "danger");
                navigate("/orders");
            }
        }
        loadOrder();
    }, [id, reloadKey]);

    // Objednávka v kalendáři je doplňkový údaj — většina zakázek žádnou nemá (404 je normální stav),
    // takže se načítá zvlášť a její selhání nesmí shodit celou stránku.
    useEffect(() => {
        api.get(`/appointments/by-order/${id}`)
            .then(setAppointment)
            .catch(() => setAppointment(null));
    }, [id]);

    if (!order) return <LoadingState />;

    const vehicleDisplayName = `${order.vehicleBrand ?? ""} ${order.vehicleModel ?? ""}`.trim();

    return (
        <div>

            <PageHeader
                title={order.orderNumber}
                subtitle={`${order.customerDisplayName} · ${vehicleDisplayName || '—'}`}
                backTo="/orders"
                avatar={<EntityAvatar name={order.customerDisplayName} />}
                badges={
                    <StatusBadge tone={getOrderStatusTone(order.status)}>
                        {getOrderStatusLabel(order.status)}
                    </StatusBadge>
                }
                actions={
                    <OrderActionButtons
                        order={order}
                        context="detail"
                        run={runOrderAction}
                        busy={busy}
                    />
                }
            />

            {/* ── Karty metrik ───────────────────────────────────────── */}
            <MetricRow>
                <MetricCard label="Odhadovaná cena (s DPH)" value={formatCurrency(order.estimatedPrice)} />
                <MetricCard label="Konečná cena (s DPH)"    value={formatCurrency(order.finalPrice)} />
                <MetricCard label="Položek"         value={items.length} />
                <MetricCard label="Zadáno"          value={formatDate(order.createdAt)} />
            </MetricRow>

            {/* ── Hlavní obsah ────────────────────────────────────────── */}
            <div className="row g-3">

                {/* Levý sloupec */}
                <div className="col-md-6">

                    {/* Zákazník a vozidlo */}
                    <DetailCard title="Zákazník a vozidlo">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Zákazník</dt>
                            <dd className="col-sm-7">
                                {order.customerId
                                    ? <a href={`/customers/${order.customerId}/detail`}>{order.customerDisplayName}</a>
                                    : (order.customerDisplayName ?? '—')}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Vozidlo</dt>
                            <dd className="col-sm-7">
                                {order.vehicleId
                                    ? <a href={`/vehicles/${order.vehicleId}/detail`}>{vehicleDisplayName || '—'}</a>
                                    : (vehicleDisplayName || '—')}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">SPZ</dt>
                            <dd className="col-sm-7 font-monospace">{order.vehicleLicensePlate ?? '—'}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">VIN</dt>
                            <dd className="col-sm-7 font-monospace small">{order.vehicleVin ?? '—'}</dd>

                            {/* Stav z okamžiku příjmu (snímek na zakázce), ne aktuální stav vozu —
                                to je údaj, který zákazník podepsal na zakázkovém listu. */}
                            <dt className="col-sm-5 text-muted fw-normal">Tachometr při příjmu</dt>
                            <dd className="col-sm-7">
                                {order.mileageKmAtIntake != null
                                    ? `${formatNumber(order.mileageKmAtIntake)} km`
                                    : <span className="text-muted">nezaznamenán</span>}
                            </dd>
                        </dl>
                    </DetailCard>

                    {/* Termíny */}
                    <DetailCard title="Termíny">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Odhadované dokončení</dt>
                            <dd className={`col-sm-7 ${getEstimateDateColor(order.estimatedCompletionAt)}`}>
                                {formatDate(order.estimatedCompletionAt)}
                            </dd>

                            <dt className="col-sm-5 text-muted fw-normal">Skutečné dokončení</dt>
                            <dd className="col-sm-7">{formatDate(order.completedAt)}</dd>

                            {appointment && (
                                <>
                                    <dt className="col-sm-5 text-muted fw-normal">Vzniklo z objednávky</dt>
                                    <dd className="col-sm-7">
                                        <button type="button" className="btn btn-link p-0 text-start"
                                                onClick={() => navigate("/schedule")}>
                                            {formatDate(appointment.startsAt)} — {appointment.title}
                                        </button>
                                    </dd>
                                </>
                            )}
                        </dl>
                    </DetailCard>
                </div>

                {/* Pravý sloupec */}
                <div className="col-md-6">

                    {/* Popis */}
                    <DetailCard title="Popis zakázky">
                        <p className="mb-0 small">{order.description ?? '—'}</p>
                    </DetailCard>

                    {/* Interní poznámka — zobrazí se pouze pokud existuje */}
                    {order.internalNote && (
                        <DetailCard title={<><i className="bi bi-lock me-1"></i>Interní poznámka</>}>
                            <p className="text-muted fst-italic mb-0 small">{order.internalNote}</p>
                        </DetailCard>
                    )}

                    {/* Metadata */}
                    <DetailCard title="Metadata">
                        <dl className="row mb-0">
                            <dt className="col-sm-5 text-muted fw-normal">Zadáno</dt>
                            <dd className="col-sm-7 small">{formatDate(order.createdAt)}</dd>

                            <dt className="col-sm-5 text-muted fw-normal">Aktualizováno</dt>
                            <dd className="col-sm-7 small">{formatDate(order.updatedAt)}</dd>
                        </dl>
                    </DetailCard>
                </div>
            </div>

            {/* ── Položky zakázky (read-only) ─────────────────────────── */}
            <OrderItemsReadOnly items={items} />

            {orderDialogs}
        </div>
    );
}

/* ── Sub-komponenty ─────────────────────────────────────────────── */


function OrderItemsReadOnly({ items }) {

    const total = items.reduce(
        (sum, item) => sum + (Number(item.quantity) * Number(item.unitPrice)),
        0
    );

    // Po řádcích, ne jednou sazbou nad součtem — položky mohou mít různé sazby DPH.
    const totalGross = items.reduce(
        (sum, item) => sum + withVat(Number(item.quantity) * Number(item.unitPrice), item.vatRate),
        0
    );

    // Součet je pod tabulkou, ne v `tfoot`: stejně jako u faktury patří souhrn
    // vedle tabulky, ne do ní — a `DataTable` je jedna pro celou aplikaci.
    const columns = [
        {
            key: "itemType", header: "Typ", sortable: true,
            sortValue: i => getOrderItemTypeLabel(i.itemType),
            render: i => (
                <StatusBadge tone="secondary">{getOrderItemTypeLabel(i.itemType)}</StatusBadge>
            ),
        },
        {
            key: "name", header: "Název", sortable: true, sortValue: i => i.name,
            render: i => <OrderItemName item={i}/>,
        },
        {
            key: "quantity", header: "Mn.", sortable: true, align: "end",
            sortValue: i => Number(i.quantity), render: i => formatNumber(i.quantity),
        },
        { key: "unit", header: "Jedn.", render: i => i.unit },
        {
            // Rezervace vs. výdej — do 2026-08-07 vypadaly oba stavy stejně jako ruční materiál.
            key: "stock", header: "Sklad",
            render: i => <OrderItemStock item={i}/>,
        },
        {
            key: "unitPrice", header: "Cena / ks bez DPH", sortable: true, align: "end",
            sortValue: i => Number(i.unitPrice), render: i => formatCurrency(i.unitPrice),
        },
        { key: "vatRate", header: "DPH", align: "end", render: i => `${i.vatRate} %` },
        {
            // Ceny s DPH: zákazník uvažuje v částce, kterou zaplatí. Souhrn pod tabulkou
            // je měl, jednotlivé řádky ne — přitom právě nad řádkem se cena domlouvá.
            key: "unitGross", header: "Cena / ks s DPH", sortable: true, align: "end",
            sortValue: i => withVat(i.unitPrice, i.vatRate) ?? 0,
            render: i => formatCurrency(withVat(i.unitPrice, i.vatRate)),
        },
        {
            key: "lineTotal", header: "Celkem bez DPH", sortable: true, align: "end",
            sortValue: i => Number(i.quantity) * Number(i.unitPrice),
            className: "text-muted",
            render: i => formatCurrency(Number(i.quantity) * Number(i.unitPrice)),
        },
        {
            key: "lineTotalGross", header: "Celkem s DPH", sortable: true, align: "end",
            sortValue: i => withVat(Number(i.quantity) * Number(i.unitPrice), i.vatRate) ?? 0,
            className: "fw-semibold",
            render: i => formatCurrency(withVat(Number(i.quantity) * Number(i.unitPrice), i.vatRate)),
        },
    ];

    return (
        <DetailCard title="Položky zakázky" className="mt-3">
            <DataTable
                columns={columns}
                rows={items}
                clientSort
                dense
                emptyState={
                    <EmptyState icon="list-ul" title="Zakázka nemá žádné položky."
                                hint="Položky přidáte v editaci zakázky." />
                }
            />

            {items.length > 0 && (
                <dl className="row mb-0 mt-2 text-end justify-content-end">
                    <dt className="col-sm-3 text-muted fw-normal">Celkem bez DPH</dt>
                    <dd className="col-sm-2 text-muted mb-0">{formatCurrency(total)}</dd>

                    {/* Součet s DPH se sčítá po řádcích, ne z celkové částky bez DPH: každá
                        položka může mít jinou sazbu (materiál 21 %, některé práce 12 %),
                        takže jedna sazba nad součtem by dala jiné číslo než faktura. */}
                    <dt className="col-sm-3 fw-normal">Celkem s DPH</dt>
                    <dd className="col-sm-2 fw-semibold mb-0">{formatCurrency(totalGross)}</dd>
                </dl>
            )}
        </DetailCard>
    );
}
