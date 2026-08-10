import { useEffect, useState } from 'react'
import { requireAuth } from '../api/auth.js'

/**
 * Route guard: dokud neproběhne ověření přihlášení (`/auth/me`), zobrazí
 * vycentrovaný spinner místo chráněného obsahu — žádný záblesk dat před
 * případným 401 redirectem. Po úspěchu vrátí `children`; při neúspěchu
 * `requireAuth()` samo přesměruje na /login a guard zůstává ve stavu
 * "checking" (komponenta se stejně odmountuje s navigací pryč).
 */
export default function RequireAuth({ children }) {
    const [checking, setChecking] = useState(true)

    useEffect(() => {
        let cancelled = false
        requireAuth().then(data => {
            if (!cancelled && data) {
                setChecking(false)
            }
        })
        return () => { cancelled = true }
    }, [])

    if (checking) {
        return (
            <div className="d-flex justify-content-center align-items-center vh-100">
                <div className="spinner-border text-primary" role="status">
                    <span className="visually-hidden">Načítání...</span>
                </div>
            </div>
        )
    }

    return children
}
