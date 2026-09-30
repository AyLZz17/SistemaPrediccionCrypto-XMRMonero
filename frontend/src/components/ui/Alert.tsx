import clsx from 'clsx'
import type { ReactNode } from 'react'
import { toApiError, type ApiError } from '../../api/errors'

export type AlertTone = 'info' | 'success' | 'warning' | 'danger'

const TONES: Record<AlertTone, { wrap: string; icon: ReactNode; role: 'alert' | 'status' }> = {
  info: {
    wrap: 'border-accent-cyan/40 bg-accent-cyan-soft text-accent-cyan',
    icon: (
      <path d="M8 1.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13zM8 5v4.2M8 11.2v.6" strokeLinecap="round" />
    ),
    role: 'status',
  },
  success: {
    wrap: 'border-accent-green/40 bg-accent-green-soft text-accent-green',
    icon: <path d="M3 8.4l3.2 3.1L13 4.8" strokeLinecap="round" strokeLinejoin="round" />,
    role: 'status',
  },
  warning: {
    wrap: 'border-accent-amber/45 bg-accent-amber-soft text-accent-amber',
    icon: <path d="M8 2.2 14.4 13H1.6L8 2.2zM8 6.4v3M8 11.2v.5" strokeLinecap="round" strokeLinejoin="round" />,
    role: 'alert',
  },
  danger: {
    wrap: 'border-accent-red/50 bg-accent-red-soft text-accent-red',
    icon: (
      <>
        <circle cx="8" cy="8" r="6.5" />
        <path d="M8 4.8v3.6M8 10.8v.5" strokeLinecap="round" />
      </>
    ),
    role: 'alert',
  },
}

export interface AlertProps {
  tone?: AlertTone
  title?: ReactNode
  children?: ReactNode
  action?: ReactNode
  className?: string
}

/** Neutral, non-alarming notice used for the R-11 legal disclaimer. */
export function Notice({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={clsx(
        'flex gap-3 rounded-md border border-hairline bg-surface-inset px-4 py-3 text-sm text-ink-secondary',
        className,
      )}
    >
      <svg viewBox="0 0 16 16" className="mt-0.5 h-4 w-4 shrink-0 text-ink-muted" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
        <circle cx="8" cy="8" r="6.5" />
        <path d="M8 7.4v3.6M8 4.9v.5" strokeLinecap="round" />
      </svg>
      <div className="min-w-0 leading-normal">{children}</div>
    </div>
  )
}

export function Alert({ tone = 'info', title, children, action, className }: AlertProps) {
  const config = TONES[tone]
  return (
    <div
      role={config.role}
      className={clsx(
        'flex items-start gap-3 rounded-md border px-4 py-3 text-sm',
        config.wrap,
        className,
      )}
    >
      <svg
        viewBox="0 0 16 16"
        className="mt-0.5 h-4 w-4 shrink-0"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        aria-hidden="true"
      >
        {config.icon}
      </svg>
      <div className="min-w-0 flex-1">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children ? <div className="leading-normal opacity-95">{children}</div> : null}
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  )
}

export interface ApiErrorAlertProps {
  error: unknown
  onRetry?: () => void
  className?: string
  title?: string
}

/**
 * Renders any thrown value as a distinct, friendly alert per HTTP status
 * (401/403/429/500/timeout/network) plus the `requestId` for log correlation.
 */
export function ApiErrorAlert({ error, onRetry, className, title }: ApiErrorAlertProps) {
  const apiError: ApiError = toApiError(error)
  const tone: AlertTone = apiError.rateLimited
    ? 'warning'
    : apiError.status !== null && apiError.status < 500
      ? 'warning'
      : 'danger'

  return (
    <Alert
      tone={tone}
      title={title ?? `Error ${apiError.status ?? apiError.kind.toUpperCase()}`}
      className={className}
      action={
        onRetry && apiError.retryable ? (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-md border border-current/40 px-3 py-1.5 text-xs font-medium transition-colors duration-fast hover:bg-white/10"
          >
            Reintentar
          </button>
        ) : null
      }
    >
      <p>{apiError.friendlyMessage}</p>
      {apiError.fieldErrors.length > 0 ? (
        <ul className="mt-2 list-inside list-disc space-y-0.5 font-mono text-xs">
          {apiError.fieldErrors.map((fieldError) => (
            <li key={`${fieldError.field}-${fieldError.message}`}>
              {fieldError.field}: {fieldError.message}
            </li>
          ))}
        </ul>
      ) : null}
      {apiError.requestId ? (
        <p className="mt-2 font-mono text-xs opacity-80">requestId: {apiError.requestId}</p>
      ) : null}
    </Alert>
  )
}
