import VehicleForm from "../components/VehicleForm.jsx";
import {api, problemMessage} from "../api/api.js";
import {useNavigate} from "react-router-dom";
import {useAlert} from "../context/AlertContext.jsx";

export default function VehiclesPageCreate() {

    const navigate = useNavigate();
    const {addAlert} = useAlert();

    const initialData = {
        id:                    "",
        customerId:            "",
        vin:                   "",
        machineSerialNumber:   "",
        licensePlate:          "",
        brand:                 "",
        model:                 "",
        yearOfManufacture:     "",
        firstRegistrationDate: "",
        fuelType:              "",
        transmission:          "",
        engineDisplacementCcm: "",
        enginePowerKw:         "",
        engineCode:            "",
        color:                 "",
        initialMileageKm:      "",
        internalNote:          "",
    };

    async function onSave(formData) {
        try {
            await api.post("/vehicles", formData);
            addAlert("Vozidlo bylo uloženo", "success");
            navigate("/vehicles");
        } catch (err) {
            addAlert(problemMessage(err, "Vozidlo se nepodařilo uložit."), "danger");
        }
    }

    return (
        <VehicleForm
            initialData={initialData}
            onSave={onSave}
            title="Vytvoření nového vozidla"
            showInitialMileage
        />
    );
}
