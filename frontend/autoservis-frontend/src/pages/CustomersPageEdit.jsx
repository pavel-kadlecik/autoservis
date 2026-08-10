import React, {useState, useEffect} from 'react';
import {api, problemMessage} from "../api/api.js";
import {useNavigate, useParams} from "react-router-dom";
import CustomerForm from "../components/CustomerForm.jsx";
import {useAlert} from "../context/AlertContext.jsx";
import {splitAddresses, toUpdatePayload} from "../api/customerPayload.js";

const CustomersPageEdit = ({backPath = "/customers"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();
    const {id} = useParams();
    const [customer, setCustomer] = useState(null);

    useEffect(() => {
        async function loadCustomer() {
            try {
                const data = await api.get(`/customers/${id}`);
                setCustomer({
                    firstName:               data.firstName               ?? "",
                    lastName:                data.lastName                ?? "",
                    birthDate:               data.birthDate               ?? "",
                    companyName:             data.companyName             ?? "",
                    customerType:            data.customerType            ?? "",
                    ico:                     data.ico                     ?? "",
                    dic:                     data.dic                     ?? "",
                    legalForm:               data.legalForm               ?? "",
                    primaryEmail:            data.primaryEmail            ?? "",
                    primaryPhone:            data.primaryPhone            ?? "",
                    gdprConsent:             data.gdprConsent             ?? false,
                    marketingConsent:        data.marketingConsent        ?? false,
                    preferredContactChannel: data.preferredContactChannel ?? "EMAIL",
                    internalNote:            data.internalNote            ?? "",

                    // Adresy formulář v edit režimu needituje (UpdateRequest je nezná),
                    // ale CustomerForm je čte — bez nich komponenta spadne.
                    ...splitAddresses(data.addresses),
                });
            } catch (error) {
                addAlert(problemMessage(error, "Zákazníka se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadCustomer();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.put(`/customers/${id}`, toUpdatePayload(formData));
            addAlert("Editace zákazníka byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Zákazníka se nepodařilo editovat."), "danger");
        }
    }

    return (
        <>
            {customer &&
                <CustomerForm
                    initialData={customer}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace zákazníka"
                    isEditMode={true}
                />
            }
        </>
    );
};

export default CustomersPageEdit;
