import React, {useEffect, useRef, useState} from "react";
import {api, problemMessage} from "../api/api.js";
import PageHeader from "./PageHeader.jsx";
import FormSection from "./FormSection.jsx";
import RequiredMark from "./RequiredMark.jsx";
import {focusFirstInvalid} from "../api/formUtils.js";
import FormActions from "./FormActions.jsx";

/**
 * Sdílený formulář pro založení i editaci uživatelského účtu.
 *
 * @param {Object}   initialData       - předvyplněné hodnoty formuláře (prázdné stringy při zakládání, data z API při editaci)
 * @param {Function} onSave(formData)  - volá se s aktuálním stavem formuláře při odeslání
 * @param {Function} onCancel()        - volá se při kliknutí na tlačítko zpět
 * @param {string}   title             - nadpis stránky
 * @param {boolean}  [isEditMode=true] - true = editace (uživatelské jméno zamčené, bez pole pro heslo)
 */
export default function UserForm({initialData, onSave, onCancel, title, isEditMode = true}) {

    const [formData, setFormData] = useState(initialData);
    const [roles, setRoles] = useState([]);
    const [rolesError, setRolesError] = useState("");
    const [validated, setValidated] = useState(false);
    const userForm = useRef(null);

    useEffect(() => {
        // Bez `.catch` skončilo selhání jako unhandled rejection a sekce rolí zůstala
        // prázdná bez vysvětlení (audit KN-14) — přitom role jsou to hlavní, co se tu nastavuje.
        async function loadRoles() {
            try {
                setRoles(await api.get("/code-lists/roles"));
                setRolesError("");
            } catch (err) {
                setRoles([]);
                setRolesError(problemMessage(err, "Seznam rolí se nepodařilo načíst."));
            }
        }
        loadRoles();
    }, []);

    const handleChange = (e) => {
        const {name, value} = e.target;
        setFormData(prev => ({...prev, [name]: value}));
    };

    const handleRoleToggle = (roleId) => {
        setFormData(prev => {
            const roleIds = prev.roleIds.includes(roleId)
                ? prev.roleIds.filter(id => id !== roleId)
                : [...prev.roleIds, roleId];
            return {...prev, roleIds};
        });
    };

    function handleSave() {
        setValidated(true);
        if (userForm.current.checkValidity() && formData.roleIds.length > 0) {
            setValidated(false);
            return onSave(formData);
        } else {
            requestAnimationFrame(() => focusFirstInvalid(userForm));
        }
    }

    return (
        <div>
            <PageHeader title={title} />
            <p className="text-muted small">
                Pole označená <RequiredMark /> jsou povinná.
            </p>
            <form ref={userForm}
                  className={`needs-validation ${validated ? 'was-validated' : ''}`}
                  noValidate>

                <FormSection title="Přihlašovací údaje">
                <div className="row">
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="username">Uživatelské jméno <RequiredMark /></label>
                        <input type="text" id="username" name="username" className="form-control"
                               value={formData.username} onChange={handleChange}
                               minLength={3} maxLength={20} disabled={isEditMode} required/>
                        <div className="invalid-feedback">Zadejte uživatelské jméno (3–20 znaků)</div>
                    </div>
                    <div className="col-md-4">
                        <label className="form-label" htmlFor="email">Email <RequiredMark /></label>
                        <input type="email" id="email" name="email" className="form-control"
                               value={formData.email} onChange={handleChange} required/>
                        <div className="invalid-feedback">Zadejte platnou emailovou adresu</div>
                    </div>
                    {!isEditMode && (
                        <div className="col-md-4">
                            <label className="form-label" htmlFor="password">Heslo <RequiredMark /></label>
                            <input type="password" id="password" name="password" className="form-control"
                                   value={formData.password} onChange={handleChange}
                                   minLength={8} required/>
                            <div className="invalid-feedback">Heslo musí mít alespoň 8 znaků</div>
                        </div>
                    )}
                </div>
                </FormSection>

                <FormSection title="Role">
                    {rolesError && (
                        <div className="alert alert-danger py-2" role="alert">{rolesError}</div>
                    )}
                    <div className="row">
                        {roles.map(role => (
                            <div className="col-md-4 form-check" key={role.id}>
                                <input type="checkbox" className="form-check-input" id={`role-${role.id}`}
                                       checked={formData.roleIds.includes(role.id)}
                                       onChange={() => handleRoleToggle(role.id)}/>
                                <label className="form-check-label" htmlFor={`role-${role.id}`}>
                                    {role.description || role.name}
                                </label>
                            </div>
                        ))}
                    </div>
                    {validated && formData.roleIds.length === 0 && (
                        <div className="text-danger small mt-2">Vyberte alespoň jednu roli</div>
                    )}
                </FormSection>

                <FormActions onCancel={onCancel} onSubmit={handleSave} />
            </form>
        </div>
    );
}
