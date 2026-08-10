import React, {useState, useEffect} from 'react';
import {api, problemMessage} from "../api/api.js";
import {useNavigate, useParams} from "react-router-dom";
import UserForm from "../components/UserForm.jsx";
import {useAlert} from "../context/AlertContext.jsx";

const UsersPageEdit = ({backPath = "/users"}) => {

    const navigate = useNavigate();
    const {addAlert} = useAlert();
    const {id} = useParams();
    const [user, setUser] = useState(null);

    useEffect(() => {
        async function loadUser() {
            try {
                const data = await api.get(`/users/${id}`);
                setUser({
                    username: data.username ?? "",
                    email:    data.email    ?? "",
                    roleIds:  (data.roles ?? []).map(r => r.id),
                });
            } catch (error) {
                addAlert(problemMessage(error, "Uživatele se nepodařilo načíst."), "danger");
                navigate(backPath);
            }
        }

        if (id) loadUser();
    }, [id]);

    function onCancel() {
        navigate(backPath);
    }

    async function onSave(formData) {
        try {
            const {email, roleIds} = formData;
            await api.put(`/users/${id}`, {email, roleIds});
            addAlert("Editace uživatele byla provedena", "success");
            navigate(backPath);
        } catch (err) {
            const message = problemMessage(err, "Uživatele se nepodařilo editovat.");
            addAlert(message, "danger");
        }
    }

    return (
        <>
            {user &&
                <UserForm
                    initialData={user}
                    onSave={onSave}
                    onCancel={onCancel}
                    title="Editace uživatele"
                    isEditMode={true}
                />
            }
        </>
    );
};

export default UsersPageEdit;
