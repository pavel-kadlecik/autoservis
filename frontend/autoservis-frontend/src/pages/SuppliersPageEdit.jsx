import React, { useState, useEffect } from 'react';
import { api, problemMessage } from "../api/api.js";
import { useNavigate, useParams } from "react-router-dom";
import SupplierForm from "../components/SupplierForm.jsx";
import { useAlert } from "../context/AlertContext.jsx";

const SuppliersPageEdit = ({ backPath = "/suppliers" }) => {

    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const { id } = useParams();
    const [supplier, setSupplier] = useState(null);

    useEffect(() => {
        async function loadSupplier() {
            try {
                const data = await api.get(`/warehouse/suppliers/${id}`);
                setSupplier({
                    name:               data.name               ?? "",
                    registrationNumber: data.registrationNumber ?? "",
                    vatId:              data.vatId              ?? "",
                    street:             data.street             ?? "",
                    city:               data.city               ?? "",
                    postalCode:         data.postalCode         ?? "",
                    countryCode:        data.countryCode        ?? "",
                    bankAccount:        data.bankAccount        ?? "",
                    iban:               data.iban               ?? "",
                    swift:              data.swift              ?? "",
                    email:              data.email              ?? "",
                    phone:              data.phone              ?? "",
                });
            } catch (error) {
                addAlert(problemMessage(error, "Dodavatele se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadSupplier();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.put(`/warehouse/suppliers/${id}`, formData);
            addAlert("Editace dodavatele byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            const message = problemMessage(err, "Dodavatele se nepodařilo editovat.");
            addAlert(message, "danger");
        }
    }

    return (
        <>
            {supplier &&
                <SupplierForm
                    initialData={supplier}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace dodavatele"
                />
            }
        </>
    );
};

export default SuppliersPageEdit;
