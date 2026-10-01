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
      className="sticky top-0 z-20 flex h-header shrink-0 items-center gap-3 border-b border-hairline-subtle bg-surface-1/85 px-3 backdrop-blur-glass sm:px-5"
    >
      <button
        type="button"
        onClick={onMenuClick}
        aria-label="Abrir menú de navegación"
        className="rounded-md border border-hairline p-2 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink lg:hidden"
      >
        <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
          <path d="M2 4h12M2 8h12M2 12h12" strokeLinecap="round" />
        </svg>
      </button>

      <Link to="/dashboard" className="flex items-center gap-2.5 lg:hidden">
        <BrandMark size={26} />
        <span className="text-sm font-semibold text-ink">XMR-Forecast</span>
      </Link>

      <div className="ml-auto flex items-center gap-2 sm:gap-3">
        <div
          className="hidden items-center gap-3 rounded-md border border-hairline-subtle bg-surface-inset px-3 py-1.5 sm:flex"
          aria-label="Precio de XMR-USD"
        >
          <StatusDot tone="active" label={DEFAULT_SYMBOL} pulse={quote.isFetching} />
          <span className="font-mono text-sm font-semibold tabular-nums text-ink">
            {formatUsd(quote.data?.price)}
          </span>
          {typeof changePercent === 'number' ? (
            <span
              className={clsx(
                'font-mono text-xs tabular-nums',
                isUp ? 'text-accent-green' : 'text-accent-red',
              )}
            >
              {isUp ? '+' : ''}
              {changePercent.toFixed(2)}%
            </span>
          ) : null}
        </div>

        <StatusDot
          tone={quote.isError ? 'danger' : quote.isPending ? 'warning' : 'success'}
          label={quote.isError ? 'Sin conexión' : quote.isPending ? 'Conectando' : 'En línea'}
          pulse={quote.isFetching && !quote.isError}
          className="hidden md:inline-flex"
          data-testid="api-connection-status"
        />

        <div className="relative">
          <button
            type="button"
            onClick={() => setMenuOpen((value) => !value)}
            aria-expanded={menuOpen}
            aria-haspopup="menu"
            aria-label={`Notificaciones${unread > 0 ? `, ${unread} sin leer` : ''}`}
            className="relative rounded-md border border-hairline p-2 text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
          >
            <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="M8 1.8a4 4 0 0 0-4 4v2.4L2.8 10.4h10.4L12 8.2V5.8a4 4 0 0 0-4-4z" strokeLinejoin="round" />
              <path d="M6.5 12.2a1.6 1.6 0 0 0 3 0" strokeLinecap="round" />
            </svg>
            {unread > 0 ? (
              <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-accent-red px-1 font-mono text-[10px] font-bold text-bg-root">
                {unread}
              </span>
            ) : null}
          </button>

          {menuOpen ? (
            <div
              ref={menuRef}
              role="menu"
              aria-label="Notificaciones"
              className="absolute right-0 top-full z-30 mt-2 w-72 animate-fade-in rounded-lg border border-hairline bg-surface-2 p-2 shadow-card-lg"
            >
              <p className="label-caps px-2 py-1.5">Notificaciones</p>
              {notifications.data && notifications.data.items.length > 0 ? (
                <ul className="max-h-72 space-y-1 overflow-y-auto">
                  {notifications.data.items.map((item) => (
                    <li key={item.id}>
                      <button
                        type="button"
                        role="menuitem"
                        onClick={() => {
                          void markNotificationRead(item.id).catch(() => undefined)
                          setMenuOpen(false)
                        }}
                        className="w-full rounded-md px-2 py-2 text-left transition-colors duration-fast hover:bg-surface-3"
                      >
                        <span className="flex items-center gap-2">
                          {!item.read ? (
                            <span aria-label="Sin leer" className="h-1.5 w-1.5 rounded-full bg-accent-cyan" />
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

        <div className="hidden flex-col items-end leading-tight border-l border-hairline-subtle pl-3 md:flex">
          {/* Identidad visible en la esquina superior derecha: nombre y correo
              del titular mas su rol (T-043). */}
          <span data-testid="header-identity" className="max-w-[200px] truncate text-xs font-medium text-ink">
            {user?.fullName || user?.email}
          </span>
          <span className="max-w-[200px] truncate font-mono text-[10px] text-ink-muted">
            {user?.email}
          </span>
          <span className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">
            {user?.role}
          </span>
        </div>

        <Link
          to="/account"
          className="hidden rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink sm:inline-block"
        >
          Configuración
        </Link>

        <Link
          to="/dashboard"
          className="hidden rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink sm:inline-block"
        >
          Dashboard
        </Link>

        <button
          type="button"
          onClick={() => {
            logout(null)
            navigate('/login', { replace: true })
          }}
          className="rounded-md border border-hairline px-3 py-2 font-mono text-xs uppercase tracking-wide text-ink-secondary transition-colors duration-fast hover:border-accent-red/40 hover:text-accent-red"
        >
          Salir
        </button>
      </div>
    </header>
  )
}