import React, {useEffect, useState} from 'react';
import {useNavigate, useParams} from "react-router-dom";
import {api, problemMessage} from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import OrderForm from "../components/OrderForm.jsx";
import { useOrderActions, OrderActionButtons } from "../components/orderActions.jsx";
import { toDatetimeLocal } from "../api/format.js";


const OrdersPageEdit = ({backPath = "/orders"}) => {

    const {id} = useParams();
    const navigate = useNavigate();
    const {addAlert} = useAlert();

    const [order, setOrder] = useState(null);
    const [items, setItems] = useState([]);
    const [reloadKey, setReloadKey] = useState(0);

    // Táž sada akcí jako na detailu (rozhodnutí uživatele 2026-08-07) — jeden zdroj
    // v orderActions.jsx, aby se místa nemohla rozejít.
    const { run: runOrderAction, dialogs: orderDialogs, busy } = useOrderActions({
        onChanged: () => setReloadKey(k => k + 1),
        onDeleted: () => navigate(backPath),
    });

    useEffect(() => {
        async function loadOrder() {
            try {
                const [data, itemsData] = await Promise.all([
                    api.get(`/orders/${id}`),
                    api.get(`/orders/${id}/items`),
                ]);

                setOrder({
                    // Celá zakázka pro sdílené akce v hlavičce (stav, PDF, výdej,
                    // fakturace, mazání) — potřebují invoiceStatus i customerId.
                    raw: data,
                    formData: {
                        status:                data.status                ?? "",
                        description:           data.description           ?? "",
                        internalNote:          data.internalNote          ?? "",
                        estimatedCompletionAt: toDatetimeLocal(data.estimatedCompletionAt),
                        completedAt:           toDatetimeLocal(data.completedAt),
                        estimatedPrice:        data.estimatedPrice        ?? "",
                        finalPrice:            data.finalPrice            ?? "",
                        // PUT je full-replace — kdyby se pole nenačetlo, editace zakázky by
                        // tachometr z příjmu smazala.
                        mileageKmAtIntake:     data.mileageKmAtIntake     ?? "",
                        // LocalDate chodí jako "YYYY-MM-DD" — přesně formát <input type="date">
                        receivedAt:            data.receivedAt            ?? "",
                    },
                    readOnly: {
                        orderNumber:         data.orderNumber,
                        customerId:          data.customerId,
                        customerDisplayName: data.customerDisplayName,
                        vehicleDisplayName:  `${data.vehicleBrand} ${data.vehicleModel}`,
                        vehicleLicensePlate: data.vehicleLicensePlate,
                    }
                });

                setItems(itemsData ?? []);

            } catch (error) {
                addAlert(problemMessage(error, "Zakázku se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadOrder();
    }, [id, reloadKey]);

    function handleCancel() {
        navigate(backPath);
    }

    // Uložení NEODCHÁZÍ ze stránky (rozhodnutí uživatele 2026-08-09) — obsluha typicky
    // pokračuje položkami či tiskem listu. Znovunačtení (reloadKey) stáhne stav ze
    // serveru, protože PUT může doplnit odvozené hodnoty (např. completedAt při
    // přechodu na Dokončena). Navigaci zpět na přehled dělá tlačítko „Zpět".
    async function handleSave(formData) {
        try {
            await api.put(`/orders/${id}`, formData);
            addAlert("Zakázka byla úspěšně upravena.", "success");
            setReloadKey(k => k + 1);
        } catch (err) {
            addAlert(problemMessage(err, "Zakázku se nepodařilo uložit."), "danger");
        }
    }

    return (
        <>
            {order &&
                <OrderForm
                    // formData drží OrderForm v useState — bez remountu přes key by po
                    // reloadu (uložení, akce v hlavičce) zůstal formulář na starých hodnotách
                    key={reloadKey}
                    cancelLabel="Zpět"
                    initialData={order.formData}
                    initialItems={items}
                    readOnly={order.readOnly}
                    orderId={parseInt(id)}
                    onSave={handleSave}
                    onCancel={handleCancel}
                    title="Editace zakázky"
                    headerActions={
                        <OrderActionButtons
                            order={order.raw}
                            context="edit"
                            run={runOrderAction}
                            busy={busy}
                        />
                    }
                />
            }
            {orderDialogs}
        </>
    );
};

export default OrdersPageEdit;
