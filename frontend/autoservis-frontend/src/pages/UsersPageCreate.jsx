import React from 'react';
import {api, problemMessage } from "../api/api.js";
import {useNavigate} from "react-router-dom";
import UserForm from "../components/UserForm.jsx";
import {useAlert} from "../context/AlertContext.jsx";

const UsersPageCreate = ({backPath = "/users"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();

    const initialData = {
        username: "",
        email: "",
        password: "",
        roleIds: [],
    };

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            await api.post(`/users`, formData);
            addAlert("Uživatel byl vytvořen", "success");
            navigate(backPath);
        } catch (err) {
            const message = problemMessage(err, "Uživatele se nepodařilo vytvořit.");
            addAlert(message, "danger");
        }
    }

    return (
        <UserForm
            initialData={initialData}
            onSave={onSave}
            onCancel={onCancel}
            title="Vytvoření uživatele"
            isEditMode={false}
        />
    );
};

export default UsersPageCreate;
