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
    <div className="border-t-2 border-hairline-strong pt-3">
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
          className="grid gap-3 border-b border-hairline-subtle py-2.5"
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
    <div className="rounded border border-hairline-subtle bg-surface-1 p-5" role="status" aria-busy="true">
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
        'flex items-start gap-3 rounded-sm border border-dashed border-hairline-default px-4 py-5',
        className,
      )}
    >
      {icon ? (
        <span aria-hidden="true" className="mt-0.5 shrink-0 text-ink-muted">
          {icon}
        </span>
      ) : (
        <span aria-hidden="true" className="mt-1 inline-block h-2 w-2 shrink-0 bg-ink-muted" />
      )}
      <div className="min-w-0">
        <p className="text-sm font-semibold text-ink">{title}</p>
        {description ? <p className="mt-1 max-w-prose text-[13px] leading-normal text-ink-muted">{description}</p> : null}
        {action ? <div className="mt-3">{action}</div> : null}
      </div>
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
        'rounded-sm border border-accent-red/40 bg-accent-red-soft px-4 py-4',
        className,
      )}
    >
      <div className="flex items-center gap-2.5">
        <span aria-hidden="true" className="inline-block h-2.5 w-2.5 shrink-0 bg-accent-red" />
        <p className="text-sm font-semibold leading-tight text-accent-red">{title}</p>
        {onRetry ? (
          <button
            type="button"
            onClick={onRetry}
            className="ml-auto shrink-0 rounded-none border border-accent-red/50 px-3 py-1.5 font-mono text-[11px] uppercase tracking-wide text-accent-red transition-colors duration-fast hover:bg-accent-red/15"
          >
            Reintentar
          </button>
        ) : null}
        {action}
      </div>
      <p className="mt-2 text-[13px] leading-normal text-ink-secondary">{message}</p>
      {requestId ? (
        <p className="mt-2 font-mono text-[11px] tabular-nums text-ink-muted">requestId: {requestId}</p>
      ) : null}
    </div>
  )
}
