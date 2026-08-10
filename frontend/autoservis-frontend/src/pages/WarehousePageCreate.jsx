import WarehouseForm from "../components/WarehouseForm.jsx";
import { api, problemMessage } from "../api/api.js";
import { useNavigate } from "react-router-dom";
import { useAlert } from "../context/AlertContext.jsx";

export default function WarehousePageCreate() {

    const navigate = useNavigate();
    const { addAlert } = useAlert();

    const initialData = {
        sku:            "",
        name:           "",
        manufacturer:   "",
        manufacturerPartNumber: "",
        variant:        "",
        unit:           "ks",
        defaultVatRate: 21,   // základní česká sazba jako výchozí; uživatel může přepsat

        salePrice:      "",
        minStockLevel:  "",
        note:           "",
    };

    async function onSave(formData) {
        try {
            await api.post("/warehouse/products", formData);
            addAlert("Skladová položka byla uložena", "success");
            navigate("/warehouse");
        } catch (err) {
            addAlert(problemMessage(err, "Položku se nepodařilo uložit."), "danger");
        }
    }

    return (
        <WarehouseForm
            initialData={initialData}
            onSave={onSave}
            onCancel={() => navigate("/warehouse")}
            title="Nová skladová položka"
        />
    );
}
