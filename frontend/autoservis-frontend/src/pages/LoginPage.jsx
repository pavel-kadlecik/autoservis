import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, problemMessage } from '../api/api.js'

export default function LoginPage() {
    const [username, setUsername] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState(null)
    const [loading, setLoading] = useState(false)
    const navigate = useNavigate()

    async function handleSubmit(e) {
        e.preventDefault()
        setLoading(true)
        setError(null)

        try {
            await api.post('/auth/login', { username, password })
            navigate('/dashboard')
        } catch (err) {
            // Dřív se do alertu propsal `err.message`, což je surové tělo odpovědi — u nedostupného
            // serveru anglické „Failed to fetch", u 502 z proxy celá HTML stránka (audit 11-F-14).
            setError(problemMessage(err, 'Přihlášení se nezdařilo. Zkontrolujte připojení k serveru.'))
        } finally {
            setLoading(false)
        }
    }

    return (
        <div className="d-flex justify-content-center align-items-center vh-100">
            <div className="card shadow" style={{ width: '360px' }}>
                <div className="card-body p-4">

                    <h4 className="mb-4 text-center fw-semibold">
                        <i className="bi bi-wrench-adjustable-circle text-primary me-2"></i>
                        Autoservis
                    </h4>

                    {error && (
                        <div className="alert alert-danger" role="alert">{error}</div>
                    )}

                    <form onSubmit={handleSubmit}>
                        <div className="mb-3">
                            <label className="form-label" htmlFor="username">Uživatelské jméno</label>
                            {/* id + name + autoComplete: bez nich pole nemá přístupný název,
                                klik na popisek nezaostří a správce hesel je nepozná (11-F-14). */}
                            <input
                                type="text"
                                id="username"
                                name="username"
                                autoComplete="username"
                                className="form-control"
                                value={username}
                                onChange={e => setUsername(e.target.value)}
                                required
                                autoFocus
                            />
                        </div>
                        <div className="mb-3">
                            <label className="form-label" htmlFor="password">Heslo</label>
                            <input
                                type="password"
                                id="password"
                                name="password"
                                autoComplete="current-password"
                                className="form-control"
                                value={password}
                                onChange={e => setPassword(e.target.value)}
                                required
                            />
                        </div>
                        <button
                            type="submit"
                            className="btn btn-primary w-100"
                            disabled={loading}
                        >
                            {loading ? 'Přihlašuji...' : 'Přihlásit se'}
                        </button>
                    </form>

                </div>
            </div>
        </div>
    )
}