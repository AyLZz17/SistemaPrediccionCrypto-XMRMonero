import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import clsx from 'clsx'
import { fetchLatestQuote } from '../../api/market'
import { DEFAULT_SYMBOL } from '../../types'
import { useAuthStore } from '../../store/authStore'
import { fetchNotifications, markNotificationRead } from '../../api'
import { StatusDot } from '../ui'
import { BrandMark } from './BrandMark'

function formatUsd(value: number | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return new Intl.NumberFormat('es-ES', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * Top strip of the authenticated shell: symbol + live XMR-USD quote, API
 * state, notifications and identity. Flat coal bar, hairline bottom edge —
 * the red accent appears only in the unread counter and destructive hover.
 */
export function Header({ onMenuClick }: { onMenuClick: () => void }) {
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.clearSession)
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement | null>(null)

  const quote = useQuery({
    queryKey: ['market', 'latest', DEFAULT_SYMBOL],
    queryFn: () => fetchLatestQuote(DEFAULT_SYMBOL),
    refetchInterval: 60_000,
    staleTime: 30_000,
    retry: 1,
  })

  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: () => fetchNotifications(0, 5),
    staleTime: 30_000,
    retry: 1,
  })

  const unread = notifications.data?.items.filter((item) => !item.read).length ?? 0

  useEffect(() => {
    if (!menuOpen) return undefined
    const onDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setMenuOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuOpen])

  const changePercent = quote.data?.changePercent
  const isUp = typeof changePercent === 'number' && changePercent >= 0

  return (
    <header
      aria-label="Cabecera de la aplicación"
      className="sticky top-0 z-20 flex h-header shrink-0 items-center gap-2 border-b border-hairline-subtle bg-deep px-3 sm:gap-3 sm:px-4"
    >
      <button
        type="button"
        onClick={onMenuClick}
        aria-label="Abrir menú de navegación"
        className="rounded-sm border border-hairline p-2 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink lg:hidden"
      >
        <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
          <path d="M2 4h12M2 8h12M2 12h12" strokeLinecap="round" />
        </svg>
      </button>

      <Link to="/dashboard" className="flex items-center gap-2 lg:hidden" aria-label="XMR-Forecast, panel">
        <BrandMark size={24} />
        <span className="text-[13px] font-semibold tracking-tight text-ink">XMR-Forecast</span>
      </Link>

      <div
        className="hidden items-stretch gap-0 overflow-hidden rounded-sm border border-hairline-subtle bg-surface-inset sm:flex"
        aria-label="Precio de XMR-USD"
      >
        <span className="flex items-center border-r border-hairline-subtle bg-surface-2 px-2.5 font-mono text-[11px] uppercase tracking-wide text-ink-secondary">
          {DEFAULT_SYMBOL}
        </span>
        <span className="flex items-center px-2.5 font-mono text-sm font-semibold tabular-nums text-ink">
          {formatUsd(quote.data?.price)}
        </span>
        {typeof changePercent === 'number' ? (
          <span
            className={clsx(
              'flex items-center border-l border-hairline-subtle px-2.5 font-mono text-xs tabular-nums',
              isUp ? 'text-accent-green' : 'text-accent-red',
            )}
          >
            <span aria-hidden="true" className="mr-1">{isUp ? '▲' : '▼'}</span>
            {isUp ? '+' : ''}
            {changePercent.toFixed(2)}%
            <span className="sr-only">{isUp ? 'sube' : 'baja'}</span>
          </span>
        ) : null}
      </div>

      <div className="ml-auto flex items-center gap-1.5 sm:gap-2.5">
        <StatusDot
          tone={quote.isError ? 'danger' : quote.isPending ? 'warning' : 'success'}
          label={quote.isError ? 'Sin conexión' : quote.isPending ? 'Conectando' : 'En línea'}
          pulse={quote.isFetching && !quote.isError}
          className="hidden px-1 md:inline-flex"
          data-testid="api-connection-status"
        />

        <div className="relative">
          <button
            type="button"
            onClick={() => setMenuOpen((value) => !value)}
            aria-expanded={menuOpen}
            aria-haspopup="menu"
            aria-label={`Notificaciones${unread > 0 ? `, ${unread} sin leer` : ''}`}
            className="relative rounded-sm border border-hairline p-2 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
          >
            <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="M8 1.8a4 4 0 0 0-4 4v2.4L2.8 10.4h10.4L12 8.2V5.8a4 4 0 0 0-4-4z" strokeLinejoin="round" />
              <path d="M6.5 12.2a1.6 1.6 0 0 0 3 0" strokeLinecap="round" />
            </svg>
            {unread > 0 ? (
              <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-brand-solid px-1 font-mono text-[10px] font-bold text-ink-inverse">
                {unread}
              </span>
            ) : null}
          </button>

          {menuOpen ? (
            <div
              ref={menuRef}
              role="menu"
              aria-label="Notificaciones"
              className="absolute right-0 top-full z-30 mt-2 w-72 animate-fade-in rounded border border-hairline-default bg-surface-modal p-2 shadow-card-lg"
            >
              <p className="label-caps px-2 py-1.5">Notificaciones</p>
              {notifications.data && notifications.data.items.length > 0 ? (
                <ul className="max-h-72 space-y-0.5 overflow-y-auto">
                  {notifications.data.items.map((item) => (
                    <li key={item.id}>
                      <button
                        type="button"
                        role="menuitem"
                        onClick={() => {
                          void markNotificationRead(item.id).catch(() => undefined)
                          setMenuOpen(false)
                        }}
                        className="w-full rounded-sm px-2 py-2 text-left transition-colors duration-fast hover:bg-surface-3"
                      >
                        <span className="flex items-center gap-2">
                          {!item.read ? (
                            <span aria-label="Sin leer" className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent-red" />
                          ) : null}
                          <span className="truncate text-xs font-medium text-ink">{item.title}</span>
                        </span>
                        {item.body ? (
                          <span className="mt-0.5 block truncate text-[11px] text-ink-muted">{item.body}</span>
                        ) : null}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="px-2 py-3 text-xs text-ink-muted">No hay notificaciones.</p>
              )}
            </div>
          ) : null}
        </div>

        <div className="hidden min-w-0 flex-col items-end border-l border-hairline-subtle pl-2.5 leading-tight md:flex">
          <span data-testid="header-identity" className="max-w-[180px] truncate text-xs font-medium text-ink">
            {user?.fullName || user?.email}
          </span>
          <span className="max-w-[180px] truncate font-mono text-[10px] uppercase tracking-wide text-ink-muted">
            {user?.role}
          </span>
        </div>

        <Link
          to="/account"
          className="hidden rounded-sm border border-hairline px-2.5 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink sm:inline-block"
        >
          Cuenta
        </Link>

        <button
          type="button"
          onClick={() => {
            logout(null)
            navigate('/login', { replace: true })
          }}
          className="rounded-sm border border-hairline px-2.5 py-2 font-mono text-[11px] uppercase tracking-wide text-ink-secondary transition-colors duration-fast hover:border-accent-red/50 hover:text-accent-red"
        >
          Salir
        </button>
      </div>
    </header>
  )
}
