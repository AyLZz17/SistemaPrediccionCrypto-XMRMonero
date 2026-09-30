import clsx from 'clsx'
import type { ReactNode } from 'react'

/* -------------------------------------------------------------- skeletons */

export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx('skeleton-bar h-4 w-full', className)} aria-hidden="true" />
}

export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={clsx('space-y-2', className)}>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} className={index === lines - 1 ? 'w-2/3' : 'w-full'} />
      ))}
    </div>
  )
}

export function SkeletonStat() {
  return (
    <div className="rounded-lg border border-hairline-subtle bg-surface-1 p-5">
      <Skeleton className="h-3 w-24" />
      <Skeleton className="mt-3 h-7 w-32" />
      <Skeleton className="mt-3 h-3 w-20" />
    </div>
  )
}

export function SkeletonTable({ rows = 5, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div className="space-y-2" role="status" aria-label="Cargando datos">
      <span className="sr-only">Cargando datos...</span>
      {Array.from({ length: rows }, (_, rowIndex) => (
        <div
          key={rowIndex}
          className="grid gap-3 rounded-md border border-hairline-subtle bg-surface-inset p-3"
          style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
        >
          {Array.from({ length: columns }, (_, colIndex) => (
            <Skeleton key={colIndex} className="h-4" />
          ))}
        </div>
      ))}
    </div>
  )
}

export function SkeletonPanel({ children }: { children?: ReactNode }) {
  return (
    <div className="rounded-lg border border-hairline-subtle bg-surface-1 p-5" role="status" aria-busy="true">
      <span className="sr-only">Cargando...</span>
      {children ?? <SkeletonText lines={4} />}
    </div>
  )
}

/* ----------------------------------------------------------- empty states */

export interface EmptyStateProps {
  title: string
  description?: ReactNode
  icon?: ReactNode
  action?: ReactNode
  className?: string
}

export function EmptyState({ title, description, icon, action, className }: EmptyStateProps) {
  return (
    <div
      className={clsx(
        'flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-hairline bg-surface-inset px-6 py-12 text-center',
        className,
      )}
    >
      {icon ? (
        <span aria-hidden="true" className="text-ink-muted">
          {icon}
        </span>
      ) : (
        <span
          aria-hidden="true"
          className="h-9 w-9 rounded-full border border-dashed border-hairline-strong"
        />
      )}
      <div>
        <p className="text-sm font-semibold text-ink">{title}</p>
        {description ? <p className="mt-1 max-w-prose text-sm text-ink-muted">{description}</p> : null}
      </div>
      {action}
    </div>
  )
}

/* ----------------------------------------------------------- error states */

export interface ErrorStateProps {
  title?: string
  message: ReactNode
  requestId?: string | null
  onRetry?: () => void
  action?: ReactNode
  className?: string
}

export function ErrorState({
  title = 'No se pudo cargar la informacion',
  message,
  requestId,
  onRetry,
  action,
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={clsx(
        'flex flex-col items-start gap-3 rounded-lg border border-accent-red/40 bg-accent-red-soft px-5 py-6',
        className,
      )}
    >
      <div>
        <p className="text-sm font-semibold text-accent-red">{title}</p>
        <p className="mt-1 text-sm text-ink-secondary">{message}</p>
        {requestId ? (
          <p className="mt-2 font-mono text-xs text-ink-muted">requestId: {requestId}</p>
        ) : null}
      </div>
      <div className="flex flex-wrap gap-2">
        {onRetry ? (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-md border border-accent-red/50 px-3 py-1.5 text-xs font-medium text-accent-red transition-colors duration-fast hover:bg-accent-red/15"
          >
            Reintentar
          </button>
        ) : null}
        {action}
      </div>
    </div>
  )
}
