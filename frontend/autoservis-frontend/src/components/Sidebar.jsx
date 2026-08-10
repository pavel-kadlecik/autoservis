import { NavLink, useLocation } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { logout } from '../api/auth.js'
import ChangePasswordModal from './ChangePasswordModal.jsx'
import { NAV_SECTIONS, activeNavPath } from './navigation.js'

const STORAGE_KEY = 'sidebar.groups'

function loadCollapsed() {
    try {
        return new Set(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]'))
    } catch {
        return new Set()
    }
}

/**
 * Hlavní menu. Struktura přichází z `navigation.js`, tady je jen chování:
 * rozbalování skupin s perzistencí, aktivní stav a zavírání na mobilu.
 *
 * Skupina se **vždy rozbalí, když je aktivní některá její položka** — uložený
 * stav má nižší prioritu, aby uživatel neskončil na stránce, na kterou v menu
 * nevidí.
 *
 * @param {Object}   user         - z /auth/me (jméno, role)
 * @param {Function} [onCollapse] - zasune menu (šipka v hlavičce panelu)
 */
export default function Sidebar({ user, onCollapse }) {

    const [showChangePassword, setShowChangePassword] = useState(false)
    const [collapsed, setCollapsed] = useState(loadCollapsed)
    const { pathname } = useLocation()
    const activePath = activeNavPath(pathname)
    const userRoles = user?.roles ?? []
    const isAdmin = userRoles.includes('ROLE_ADMIN')

    useEffect(() => {
        localStorage.setItem(STORAGE_KEY, JSON.stringify([...collapsed]))
    }, [collapsed])

    function toggleGroup(id) {
        setCollapsed(prev => {
            const next = new Set(prev)
            if (next.has(id)) {
                next.delete(id)
            } else {
                next.add(id)
            }
            return next
        })
    }

    const visibleSections = NAV_SECTIONS.map(section => ({
        ...section,
        items: section.items.filter(item => {
            // adminOnly = jen ADMIN; roles = některá z uvedených rolí (např. správa zaměstnanců ADMIN/MANAGER)
            if (item.adminOnly && !isAdmin) return false
            if (item.roles && !item.roles.some(role => userRoles.includes(role))) return false
            return true
        }),
    })).filter(section => section.items.length > 0)

    function renderItem(item, indented) {
        // Zvýraznění řídí activeNavPath (nejdelší shoda), ne NavLink — jeho
        // vlastní isActive by u prefixových cest zvýraznil dvě položky naráz
        // (/warehouse i /warehouse/receipts). className musí být **funkce**,
        // jinak si NavLink třídu `active` přidá sám a sečte se s naší.
        const isActive = item.to === activePath
        return (
            <li className="nav-item" key={item.to}>
                <NavLink to={item.to}
                         aria-current={isActive ? 'page' : undefined}
                         className={() =>
                             'nav-link' + (indented ? ' nav-sub' : '') + (isActive ? ' active' : '')}>
                    <i className={`bi bi-${item.icon}`} aria-hidden="true"></i> {item.label}
                </NavLink>
            </li>
        )
    }

    return (
        <nav id="sidebar" className="d-flex flex-column py-3" aria-label="Hlavní menu">

            <div className="px-3 mb-4 d-flex align-items-center gap-2">
                <span className="text-white fw-bold fs-5 me-auto text-truncate">
                    <i className="bi bi-wrench-adjustable-circle text-primary me-2" aria-hidden="true"></i>
                    Autoservis
                </span>
                <button type="button" className="btn btn-sm sidebar-collapse"
                        onClick={onCollapse}
                        aria-label="Zasunout menu" aria-expanded={true}>
                    <i className="bi bi-chevron-double-left" aria-hidden="true"></i>
                </button>
            </div>

            <div className="flex-grow-1">
                {visibleSections.map(section => {
                    const hasActiveChild = section.items.some(item => item.to === activePath)
                    // uložené sbalení ustoupí, když je uvnitř aktivní stránka
                    const isOpen = !section.group || !collapsed.has(section.id) || hasActiveChild

                    return (
                        <ul className={`nav flex-column${section.separated ? ' nav-section-separated' : ''}`}
                            key={section.id}>
                            {section.group && (
                                <li className="nav-item">
                                    <button type="button"
                                            className={'nav-link nav-group-toggle text-start'
                                                + (hasActiveChild && !isOpen ? ' nav-group-active' : '')}
                                            aria-expanded={isOpen}
                                            onClick={() => toggleGroup(section.id)}>
                                        <i className={`bi bi-${section.group.icon}`} aria-hidden="true"></i>
                                        {section.group.label}
                                        <i className={`bi bi-chevron-${isOpen ? 'down' : 'right'} ms-auto small`}
                                           aria-hidden="true"></i>
                                    </button>
                                </li>
                            )}
                            {isOpen && section.items.map(item => renderItem(item, Boolean(section.group)))}
                        </ul>
                    )
                })}
            </div>

            <div className="px-3 mt-3 border-top border-secondary pt-3">
                <div className="d-flex align-items-center gap-2 mb-2">
                    <div className="bg-primary rounded-circle d-flex align-items-center justify-content-center"
                         style={{ width: '32px', height: '32px' }}>
                        <i className="bi bi-person text-white small" aria-hidden="true"></i>
                    </div>
                    <div className="text-white small fw-medium text-truncate">
                        {user ? user.username : 'Načítám…'}
                    </div>
                </div>
                <button type="button" className="nav-link px-0 border-0 bg-transparent w-100 text-start"
                        onClick={() => setShowChangePassword(true)}>
                    <i className="bi bi-key" aria-hidden="true"></i> Změnit heslo
                </button>
                <button type="button" className="nav-link text-danger px-0 border-0 bg-transparent w-100 text-start"
                        onClick={logout}>
                    <i className="bi bi-box-arrow-left" aria-hidden="true"></i> Odhlásit se
                </button>
            </div>

            <ChangePasswordModal show={showChangePassword} onClose={() => setShowChangePassword(false)} />

        </nav>
    )
}
