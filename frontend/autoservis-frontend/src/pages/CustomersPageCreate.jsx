import React from 'react';
import {api, problemMessage} from "../api/api.js";
import {useNavigate} from "react-router-dom";
import CustomerForm from "../components/CustomerForm.jsx";
import {useAlert} from "../context/AlertContext.jsx";
import {EMPTY_ADDRESS, toCreatePayload} from "../api/customerPayload.js";

const CustomersPageCreate = ({backPath = "/customers"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();

    const initialData = {
        firstName:               "",
        lastName:                "",
        birthDate:               "",
        companyName:             "",
        customerType:            "NONE",
        ico:                     "",
        dic:                     "",
        legalForm:               "",
        primaryEmail:            "",
        primaryPhone:            "",
        gdprConsent:             false,
        marketingConsent:        false,
        preferredContactChannel: "EMAIL",
        internalNote:            "",

        // --- Adresy: UI-friendly stav ---
        // Formulář pracuje se dvěma pojmenovanými adresami + přepínačem.
        // Do API tvaru (plochý seznam s typy) se to složí až v toCreatePayload.
        billingAddress:          {...EMPTY_ADDRESS},   // fakturační — vždy povinná (kotva)
        hasSeparateContact:      false,                // checkbox "kontaktní adresa je jiná"
        contactAddress:          {...EMPTY_ADDRESS},   // kontaktní — jen když je checkbox zaškrtnutý
    };

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.post(`/customers`, toCreatePayload(formData));
            addAlert("Zákazník byl vytvořen", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Zákazníka se nepodařilo vytvořit."), "danger");
        }
    }

    return (
        <CustomerForm
            initialData={initialData}
            onSave={onSave}
            onCancel={onCancel}
            title="Vytvoření zákazníka"
            isEditMode={false}
        />
    );
};

export default CustomersPageCreate;
