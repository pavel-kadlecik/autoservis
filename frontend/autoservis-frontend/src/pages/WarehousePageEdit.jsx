import React, { useState, useEffect } from "react";
import { api, problemMessage } from "../api/api.js";
import { useNavigate, useParams } from "react-router-dom";
import WarehouseForm from "../components/WarehouseForm.jsx";
import { useAlert } from "../context/AlertContext.jsx";

export default function WarehousePageEdit({ backPath = "/warehouse" }) {

    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const { id } = useParams();
    const [product, setProduct] = useState(null);

    useEffect(() => {
        async function loadProduct() {
            try {
                const data = await api.get(`/warehouse/products/${id}`);
                setProduct({
                    sku:            data.sku            ?? "",
                    name:           data.name           ?? "",
                    manufacturer:   data.manufacturer   ?? "",
                    manufacturerPartNumber: data.manufacturerPartNumber ?? "",
                    variant:        data.variant        ?? "",
                    unit:           data.unit           ?? "ks",
                    defaultVatRate: data.defaultVatRate ?? "",
                    salePrice:      data.salePrice      ?? "",
                    minStockLevel:  data.minStockLevel  ?? "",
                    note:           data.note           ?? "",
                });
            } catch (error) {
                addAlert(problemMessage(error, "Položku se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadProduct();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.put(`/warehouse/products/${id}`, formData);
            addAlert("Editace položky byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Položku se nepodařilo editovat."), "danger");
        }
    }

    return (
        <>
            {product &&
                <WarehouseForm
                    initialData={product}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace skladové položky"
                />
            }
        </>
    );
}
