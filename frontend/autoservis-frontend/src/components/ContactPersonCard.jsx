import * as React from "react";

export default function ContactPersonCard({ customer }) {

    const activePersons = customer.contactPersons.filter(p => p.isActive);

    return (
        <>
            <h3 className="h6 text-uppercase text-muted mb-3">Kontaktní osoby</h3>

            {activePersons.length === 0 ? (
                <p className="text-muted fst-italic mb-0">Žádné kontaktní osoby</p>
            ) : (
                <ul className="list-unstyled mb-0">
                    {activePersons.map((person, index) => (
                        <li key={person.id}
                            className={index < activePersons.length - 1 ? 'pb-3 mb-3 border-bottom' : ''}>
                            <dl className="row mb-0">
                                <dt className="col-sm-5 text-muted fw-normal">Jméno</dt>
                                <dd className="col-sm-7">
                                    {person.firstName ?? '—'} {person.lastName ?? ''}
                                </dd>

                                <dt className="col-sm-5 text-muted fw-normal">Pozice</dt>
                                <dd className="col-sm-7">{person.position ?? '—'}</dd>

                                <dt className="col-sm-5 text-muted fw-normal">Email</dt>
                                <dd className="col-sm-7">{person.email ?? '—'}</dd>

                                <dt className="col-sm-5 text-muted fw-normal">Telefon</dt>
                                <dd className="col-sm-7">{person.phone ?? '—'}</dd>
                            </dl>
                        </li>
                    ))}
                </ul>
            )}
        </>
    );
}