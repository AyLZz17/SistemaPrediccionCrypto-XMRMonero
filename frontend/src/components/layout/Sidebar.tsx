import { useEffect, useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import clsx from 'clsx'
import { useAuthStore } from '../../store/authStore'
import { hasAtLeast, ROLE_LABELS, type Role } from '../../types'
import { StatusDot } from '../ui'
import { BrandMark } from './BrandMark'

export interface NavItem {
  to: string
  label: string
  description: string
  minimum: Role
  glyph: string
}

export interface NavGroup {
  title: string
  items: NavItem[]
}

/**
 * Route map. `minimum` drives BOTH the sidebar filter and `RoleRoute`, so the
 * menu and the guard can never disagree (R-35: explicit authorisation).
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    title: 'Operacion',
    items: [
      { to: '/dashboard', label: 'Dashboard', description: 'Estado general del sistema', minimum: 'VIEWER', glyph: '▚' },
      { to: '/market', label: 'Mercado', description: 'Velas y precio de XMR-USD', minimum: 'VIEWER', glyph: '▤' },
      { to: '/predictions', label: 'Predicciones', description: 'Pronosticos por modelo', minimum: 'VIEWER', glyph: '◈' },
      { to: '/jobs', label: 'Jobs', description: 'Monitor de tareas', minimum: 'VIEWER', glyph: '⚙' },
    ],
  },
  {
    title: 'Modelos',
    items: [
      { to: '/experiments', label: 'Experimentos', description: 'Configuracion y corridas', minimum: 'ANALYST', glyph: '⌬' },
      { to: '/metrics', label: 'Metricas', description: 'MAE, RMSE, MAPE y direccion', minimum: 'ANALYST', glyph: '∑' },
      { to: '/models', label: 'Comparativa', description: 'LSTM/GRU vs. baselines', minimum: 'ANALYST', glyph: '⇄' },
    ],
  },
  {
    title: 'Administracion',
    items: [
      { to: '/admin', label: 'Usuarios', description: 'Roles y altas', minimum: 'ADMIN', glyph: '⚿' },
      { to: '/admin/audit', label: 'Auditoria', description: 'Traza de acciones', minimum: 'ADMIN', glyph: '❐' },
      { to: '/admin/logs', label: 'Logs', description: 'Operaciones y errores', minimum: 'ADMIN', glyph: '≡' },
    ],
  },
]

export interface SidebarProps {
  /** Mobile drawer state owned by the layout. */
  open: boolean
  onClose: () => void
}

export function Sidebar({ open, onClose }: SidebarProps) {
  const user = useAuthStore((state) => state.user)
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)

  useEffect(() => {
    onClose()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname])

  const role: Role = user?.role ?? 'VIEWER'

  return (
    <>
      {open ? (
        <div
          className="fixed inset-0 z-30 animate-fade-in-slow bg-surface-overlay lg:hidden"
          onClick={onClose}
          aria-hidden="true"
        />
      ) : null}
      <aside
        aria-label="Navegacion principal"
        className={clsx(
          'fixed inset-y-0 left-0 z-40 flex flex-col border-r border-hairline-subtle bg-surface-1/90 backdrop-blur-glass transition-transform duration-base ease-out',
          'lg:static lg:translate-x-0',
          collapsed ? 'lg:w-[76px]' : 'lg:w-sidebar',
          open ? 'translate-x-0' : '-translate-x-full',
          'w-sidebar',
        )}
      >
        <div className="flex h-header shrink-0 items-center gap-3 border-b border-hairline-subtle px-4">
          <BrandMark />
          {!collapsed ? (
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-ink">XMR-Forecast</p>
              <p className="label-caps truncate">Operations center</p>
            </div>
          ) : null}
        </div>

        <nav className="scroll-fade flex-1 space-y-6 px-3 py-4" aria-label="Secciones">
          {NAV_GROUPS.map((group) => {
            const visible = group.items.filter((item) => hasAtLeast(role, item.minimum))
            if (visible.length === 0) return null
            return (
              <div key={group.title}>
                {!collapsed ? <p className="label-caps mb-2 px-3">{group.title}</p> : null}
                <ul className="space-y-1">
                  {visible.map((item) => (
                    <li key={item.to}>
                      <NavLink
                        to={item.to}
                        title={collapsed ? item.label : undefined}
                        className={({ isActive }) =>
                          clsx(
                            'group relative flex items-center gap-3 rounded-md px-3 py-2.5 text-sm transition-all duration-fast ease-out',
                            isActive
                              ? 'border border-accent-cyan/40 bg-accent-cyan-soft text-accent-cyan shadow-glow-cyan'
                              : 'border border-transparent text-ink-secondary hover:border-hairline-subtle hover:bg-surface-2 hover:text-ink',
                          )
                        }
                      >
                        <span aria-hidden="true" className="w-4 shrink-0 text-center font-mono text-sm">
                          {item.glyph}
                        </span>
                        {!collapsed ? (
                          <span className="min-w-0 flex-1">
                            <span className="block truncate">{item.label}</span>
                            <span className="block truncate text-[11px] text-ink-muted">{item.description}</span>
                          </span>
                        ) : null}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            )
          })}
        </nav>

        <div className="shrink-0 space-y-3 border-t border-hairline-subtle px-3 py-3">
          {!collapsed && user ? (
            <div className="rounded-md border border-hairline-subtle bg-surface-inset p-3">
              <p className="truncate text-xs font-medium text-ink">{user.fullName}</p>
              <p className="truncate font-mono text-[11px] text-ink-muted">{user.email}</p>
              <div className="mt-2 flex items-center justify-between">
                <span className="label-caps">Rol</span>
                <span className="font-mono text-[11px] text-accent-cyan">{ROLE_LABELS[user.role]}</span>
              </div>
            </div>
          ) : null}
          <div className="flex items-center justify-between gap-2 px-1">
            <StatusDot tone="success" label="Seguro" pulse />
            <button
              type="button"
              onClick={() => setCollapsed((value) => !value)}
              aria-pressed={collapsed}
              className="rounded-md border border-hairline px-2 py-1 font-mono text-[11px] text-ink-muted transition-colors duration-fast hover:border-hairline-strong hover:text-ink lg:block"
            >
              {collapsed ? '»' : '«'}
              <span className="sr-only"> {collapsed ? 'Expandir menu lateral' : 'Contraer menu lateral'}</span>
            </button>
          </div>
        </div>
      </aside>
    </>
  )
}
