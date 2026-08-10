import React, {useState, useEffect} from 'react';
import {api, problemMessage} from "../api/api.js";
import {useNavigate, useParams} from "react-router-dom";
import VehicleForm from "../components/VehicleForm.jsx";
import {useAlert} from "../context/AlertContext.jsx";

const VehiclesPageEdit = ({backPath = "/vehicles"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();
    const {id} = useParams();
    const [vehicle, setVehicle] = useState(null);

    useEffect(() => {
        async function loadVehicle() {
            try {
                const data = await api.get(`/vehicles/${id}`);
                setVehicle({
                    customerId:            data.customerId            ?? "",
                    customer:              data.customer              ?? null,
                    vin:                   data.vin                   ?? "",
                    machineSerialNumber:   data.machineSerialNumber   ?? "",
                    licensePlate:          data.licensePlate          ?? "",
                    brand:                 data.brand                 ?? "",
                    model:                 data.model                 ?? "",
                    yearOfManufacture:     data.yearOfManufacture     ?? "",
                    firstRegistrationDate: data.firstRegistrationDate ?? "",
                    fuelType:              data.fuelType              ?? "",
                    transmission:          data.transmission          ?? "",
                    engineDisplacementCcm: data.engineDisplacementCcm ?? "",
                    enginePowerKw:         data.enginePowerKw         ?? "",
                    engineCode:            data.engineCode            ?? "",
                    color:                 data.color                 ?? "",
                    internalNote:          data.internalNote          ?? "",
                });
            } catch (error) {
                addAlert(problemMessage(error, "Vozidlo se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadVehicle();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.put(`/vehicles/${id}`, formData);
            addAlert("Editace vozidla byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Vozidlo se nepodařilo editovat."), "danger");
        }
    }

    return (
        <>
            {vehicle &&
                <VehicleForm
                    initialData={vehicle}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace vozidla"
                />
            }
        </>
    );
};

export default VehiclesPageEdit;
