import React, {useEffect, useState} from 'react';
import {useNavigate, useSearchParams} from "react-router-dom";
import {api, problemMessage} from "../api/api.js";
import {useAlert} from "../context/AlertContext.jsx";
import OrderForm from "../components/OrderForm.jsx";
import ErrorState from "../components/ErrorState.jsx";
import {getFormDate, toDatetimeLocal} from "../api/format.js";

const EMPTY_ORDER = {
    customerId:            null,
    vehicleId:             null,
    description:           "",
    internalNote:          "",
    estimatedCompletionAt: "",
    estimatedPrice:        "",
    mileageKmAtIntake:     "",
};

/**
 * Konec objednávky jako odhad dokončení zakázky — ale jen když ještě nenastal.
 *
 * <p>U vícedenní objednávky („nechá auto do středy") konec znamená, kdy si zákazník přijede pro
 * auto, takže je to rovnou ten odhad. U ranního slotu 8–10 znamená jen konec rezervovaného okna:
 * zakázka se zakládá, až auto přijede, tedy typicky po něm. Předvyplnit ho tam nejde ani technicky —
 * `OrderDto.CreateRequest` má na poli `@FutureOrPresent`, takže by se formulář otevřel s hodnotou,
 * kterou server odmítne, a obsluha by ji musela pokaždé mazat.
 *
 * @param {string|null} endsAt konec objednávky (ISO), nebo null u objednávky bez konce
 * @returns {string} hodnota pro `datetime-local`, nebo prázdný řetězec
 */
function futureEstimate(endsAt) {
    if (!endsAt || new Date(endsAt) <= new Date()) {
        return "";
    }
    return toDatetimeLocal(endsAt);
}

/**
 * Založení zakázky — buď od nuly, nebo **z objednávky v kalendáři** (`?appointmentId=…`).
 *
 * <p>V druhém případě se formulář předvyplní z objednávky a uloží se přes
 * `POST /appointments/{id}/convert`, ne přes `POST /orders`. Rozdíl je zásadní: convert je
 * jedna transakce, ve které vznikne zakázka a zároveň se objednávka naváže a přepne na
 * `CONVERTED`. Kdyby se volalo `/orders` a propojení řešil až druhý požadavek, selhání toho
 * druhého by nechalo osiřelou zakázku, o které objednávka neví.
 */
const OrdersPageCreate = ({backPath = "/orders"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();
    const [searchParams] = useSearchParams();
    const appointmentId = searchParams.get("appointmentId");

    // receivedAt se předvyplňuje až tady (ne v EMPTY_ORDER) — modulová konstanta by si
    // zapamatovala dnešek z prvního načtení aplikace, přes půlnoc by lhala.
    const [initialData, setInitialData] = useState(
        appointmentId ? null : {...EMPTY_ORDER, receivedAt: getFormDate()});
    const [loadError, setLoadError] = useState(null);

    useEffect(() => {
        if (!appointmentId) return;

        api.get(`/appointments/${appointmentId}`)
            .then((appointment) => setInitialData({
                ...EMPTY_ORDER,
                receivedAt:  getFormDate(),
                customerId:  appointment.customerId,
                vehicleId:   appointment.vehicleId,
                description: appointment.title,
                // Poznámka z objednávky je interní — do popisu pro zákazníka nepatří.
                internalNote: appointment.note ?? "",
                estimatedCompletionAt: futureEstimate(appointment.endsAt),
                customerDisplayName:   appointment.customerDisplayName,
                // Značka a model jdou s SPZ: našeptávač vozidel píše „Značka Model - SPZ",
                // takže bez nich by předvyplněné pole vypadalo jinak než po výběru z nabídky.
                vehicleLicensePlate:   appointment.vehicleLicensePlate,
                vehicleBrand:          appointment.vehicleBrand,
                vehicleModel:          appointment.vehicleModel,
            }))
            .catch((err) => setLoadError(
                problemMessage(err, "Objednávku se nepodařilo načíst.")));
    }, [appointmentId]);

    function handleCancel() {
        navigate(appointmentId ? "/schedule" : backPath);
    }

    async function handleSave(formData) {
        try {
            if (appointmentId) {
                await api.post(`/appointments/${appointmentId}/convert`, formData);
                addAlert("Zakázka byla vytvořena a objednávka na ni navázána.", "success");
                navigate("/orders");
                return;
            }
            await api.post("/orders", formData);
            addAlert("Zakázka byla úspěšně vytvořena.", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Zakázku se nepodařilo vytvořit."), "danger");
        }
    }

    if (loadError) {
        return <ErrorState message={loadError} backTo="/schedule" backLabel="Zpět na plánování" />;
    }
    if (!initialData) {
        return null; // krátké načtení objednávky; ErrorState výše řeší selhání
    }

    return (
        <OrderForm
            initialData={initialData}
            onSave={handleSave}
            onCancel={handleCancel}
            title={appointmentId ? "Zakázka z objednávky" : "Vytvoření nové zakázky"}
            isEditMode={false}
        />
    );
};

export default OrdersPageCreate;
