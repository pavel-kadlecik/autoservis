import React, { useState, useEffect } from 'react';
import { api, problemMessage } from "../api/api.js";
import { useNavigate, useParams } from "react-router-dom";
import EmployeeForm from "../components/EmployeeForm.jsx";
import { useAlert } from "../context/AlertContext.jsx";
import { toEmployeePayload } from "../api/employeePayload.js";

const EmployeesPageEdit = ({ backPath = "/employees" }) => {

    const navigate = useNavigate();
    const { addAlert } = useAlert();
    const { id } = useParams();
    const [employee, setEmployee] = useState(null);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        async function loadEmployee() {
            try {
                const data = await api.get(`/employees/${id}`);
                setEmployee({
                    firstName:  data.firstName  ?? "",
                    lastName:   data.lastName   ?? "",
                    position:   data.position   ?? "",
                    hourlyRate: data.hourlyRate ?? "",
                    hiredAt:    data.hiredAt    ?? "",
                    leftAt:     data.leftAt     ?? "",
                });
            } catch (err) {
                addAlert(problemMessage(err, "Zaměstnance se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadEmployee();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        setSaving(true);
        try {
            await api.put(`/employees/${id}`, toEmployeePayload(formData));
            addAlert("Editace zaměstnance byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            addAlert(problemMessage(err, "Zaměstnance se nepodařilo editovat."), "danger");
        } finally {
            setSaving(false);
        }
    }

    return (
        <>
            {employee &&
                <EmployeeForm
                    initialData={employee}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace zaměstnance"
                    saving={saving}
                />
            }
        </>
    );
};

export default EmployeesPageEdit;
