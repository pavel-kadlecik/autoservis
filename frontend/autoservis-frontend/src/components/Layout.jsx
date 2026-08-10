import { Outlet, useLocation } from 'react-router-dom'
import Sidebar from './Sidebar.jsx'
import { useState, useEffect } from 'react'
import { requireAuth, logout } from '../api/auth.js'
import AlertContainer from "./AlertContainer.jsx";
import ErrorBoundary from "./ErrorBoundary.jsx";
import ChangePasswordModal from "./ChangePasswordModal.jsx";

const SIDEBAR_KEY = 'sidebar.open'

export default function Layout() {
    const [user, setUser] = useState(null)
    const location = useLocation()

    // Menu má jediné chování: vysunuto / zasunuto, stejné na všech šířkách —
    // stav nikdy nepřepíná okno, vždy jen uživatel. Dřív se stav řídil šířkou
    // okna, což bylo nepředvídatelné.
    // Pod 768 px se vysunuté menu jen POLOŽÍ PŘES obsah (CSS, `.sidebar-backdrop`):
    // vedle sebe by z 375 px zbylo obsahu 135 px.
    const [sidebarOpen, setSidebarOpen] = useState(
        () => localStorage.getItem(SIDEBAR_KEY) !== 'closed')

    // Se zasunutým menu nese změnu hesla sbalená lišta (Sidebar není vykreslený).
    const [showChangePassword, setShowChangePassword] = useState(false)

    useEffect(() => {
        requireAuth().then(data => setUser(data))
    }, [])

    useEffect(() => {
        localStorage.setItem(SIDEBAR_KEY, sidebarOpen ? 'open' : 'closed')
    }, [sidebarOpen])

    return (
        <div className="app-shell d-flex" style={{ minHeight: '100vh' }}>

            {/* Zasunuté menu se nevykresluje vůbec — schovávat ho CSS by znamenalo
                přebíjet Bootstrapí `.d-flex`, které má !important. */}
            {sidebarOpen && (
                <>
                    <Sidebar user={user} onCollapse={() => setSidebarOpen(false)} />
                    <button type="button" className="sidebar-backdrop"
                            onClick={() => setSidebarOpen(false)}
                            aria-label="Zasunout menu" />
                </>
            )}

            <AlertContainer time={15000}/>

            <main id="main-content" style={{ flex: 1 }}>
                {/* Se zasunutým menu se ukáže sbalená lišta přes celou šířku:
                    vlevo hlavička skrytého panelu (nadpis + šipka na stejném místě
                    jako v rozbaleném Sidebaru, jen šipka míří ven), vpravo ukotvený
                    účet — totéž, co je v menu pod čarou, aby po sbalení nezmizel. */}
                {!sidebarOpen && (
                    <div className="sidebar-collapsed-bar">
                        <div className="collapsed-brand">
                            <span className="sidebar-brand fs-5 me-auto text-truncate">
                                <i className="bi bi-wrench-adjustable-circle text-primary me-2" aria-hidden="true"></i>
                                Autoservis
                            </span>
                            <button type="button" className="btn btn-sm sidebar-collapse"
                                    onClick={() => setSidebarOpen(true)}
                                    aria-label="Vysunout menu" aria-expanded={false}>
                                <i className="bi bi-chevron-double-right" aria-hidden="true"></i>
                            </button>
                        </div>

                        <div className="ms-auto d-flex align-items-center gap-2">
                            <span className="d-flex align-items-center gap-2 me-1">
                                <span className="bg-primary rounded-circle d-flex align-items-center justify-content-center flex-shrink-0"
                                      style={{ width: '28px', height: '28px' }}>
                                    <i className="bi bi-person text-white small" aria-hidden="true"></i>
                                </span>
                                <span className="text-white small fw-medium text-truncate d-none d-sm-inline"
                                      style={{ maxWidth: '160px' }}>
                                    {user ? user.username : 'Načítám…'}
                                </span>
                            </span>
                            <button type="button" className="account-link"
                                    onClick={() => setShowChangePassword(true)}>
                                <i className="bi bi-key" aria-hidden="true"></i>
                                <span className="d-none d-sm-inline">Změnit heslo</span>
                            </button>
                            <button type="button" className="account-link logout" onClick={logout}>
                                <i className="bi bi-box-arrow-left" aria-hidden="true"></i>
                                <span className="d-none d-sm-inline">Odhlásit se</span>
                            </button>
                        </div>

                        <ChangePasswordModal show={showChangePassword}
                                             onClose={() => setShowChangePassword(false)} />
                    </div>
                )}

                {/* key = cesta: po pádu stačí přejít jinam a boundary se resetuje sama */}
                <ErrorBoundary key={location.pathname}>
                    <Outlet />
                </ErrorBoundary>
            </main>
        </div>
    )
}
