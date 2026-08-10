import React, { useState } from 'react';
import { api, problemMessage } from "../api/api.js";
import { useNavigate } from "react-router-dom";
import EmployeeForm from "../components/EmployeeForm.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import { toEmployeePayload } from "../api/employeePayload.js";

const EmployeesPageCreate = ({ backPath = "/employees" }) => {

    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const [saving, setSaving] = useState(false);

    const initialData = {
        firstName: "",
        lastName: "",
        position: "",
        hourlyRate: "",
        hiredAt: "",
        leftAt: "",
    };

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        setSaving(true);
        try {
            await api.post(`/employees`, toEmployeePayload(formData));
            addAlert("Zaměstnanec byl vytvořen", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Zaměstnance se nepodařilo vytvořit."), "danger");
        } finally {
            setSaving(false);
        }
    }

    return (
        <EmployeeForm
            initialData={initialData}
            onSave={onSave}
            onCancel={onCancel}
            title="Vytvoření zaměstnance"
            saving={saving}
        />
    );
};

export default EmployeesPageCreate;
