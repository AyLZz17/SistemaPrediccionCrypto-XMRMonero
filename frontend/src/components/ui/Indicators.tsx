import clsx from 'clsx'
import type { ReactNode } from 'react'

export type IndicatorTone = 'idle' | 'active' | 'success' | 'warning' | 'danger'

const DOT: Record<IndicatorTone, string> = {
  idle: 'bg-idle',
  active: 'bg-active',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
}

/** State colours for dots and compact labels (no big fills). */
const TEXT: Record<IndicatorTone, string> = {
  idle: 'text-ink-muted',
  active: 'text-active',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
}

/**
 * Values read as foreground data, so neutral tones render in ink and only real
 * state (success/warning/danger) takes colour. This keeps cyan out of KPI
 * values: cyan is reserved for focus, interaction and connectivity.
 */
const VALUE_TEXT: Record<IndicatorTone, string> = {
  idle: 'text-ink',
  active: 'text-ink',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
}

const TREND_TEXT: Record<'up' | 'down' | 'flat', string> = {
  up: 'text-success',
  down: 'text-danger',
  flat: 'text-ink-muted',
}

export interface StatusDotProps {
  tone?: IndicatorTone
  /** Live dots animate to convey ongoing activity. */
  pulse?: boolean
  label?: string
  className?: string
  'data-testid'?: string
}

/** Small non-color-only status cue: dot shape + optional text label. */
export function StatusDot({ tone = 'idle', pulse = false, label, className, ...rest }: StatusDotProps) {
  return (
    <span
      {...rest}
      className={clsx('inline-flex items-center gap-2', TEXT[tone], className)}
    >
      <span
        aria-hidden="true"
        className={clsx('relative inline-flex h-2 w-2 rounded-full', DOT[tone], pulse && 'animate-pulse')}
      />
      {label ? <span className="font-mono text-xs uppercase tracking-wide">{label}</span> : null}
    </span>
  )
}

export interface ActivityBarProps {
  label: string
  tone?: IndicatorTone
  /** 0..1 */
  progress?: number
  className?: string
}

/** Determinate/indeterminate progress with an accessible value. */
export function ActivityBar({ label, tone = 'active', progress, className }: ActivityBarProps) {
  const pct = progress === undefined ? undefined : Math.max(0, Math.min(1, progress))
  return (
    <div className={clsx('space-y-1.5', className)}>
      <div className="flex items-center justify-between gap-3">
        <span className="label-caps">{label}</span>
        {pct !== undefined ? (
          <span className="font-mono text-xs tabular-nums text-ink-secondary">
            {Math.round(pct * 100)}%
          </span>
        ) : null}
      </div>
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={pct === undefined ? undefined : 0}
        aria-valuemax={pct === undefined ? undefined : 100}
        aria-valuenow={pct === undefined ? undefined : Math.round(pct * 100)}
        className="relative h-1.5 w-full overflow-hidden rounded-full bg-surface-inset"
      >
        {pct === undefined ? (
          <span
            aria-hidden="true"
            className="absolute inset-y-0 left-0 w-1/3 rounded-full bg-active/70 animate-pulse"
          />
        ) : (
          <span
            aria-hidden="true"
            className={clsx('block h-full rounded-full transition-all duration-slow ease-out', DOT[tone])}
            style={{ width: `${pct * 100}%` }}
          />
        )}
      </div>
    </div>
  )
}

export interface MetricCardProps {
  label: string
  value: ReactNode
  unit?: string
  hint?: ReactNode
  tone?: IndicatorTone
  trend?: 'up' | 'down' | 'flat'
  className?: string
}

/** Compact KPI tile. Values are monospace for terminal-like alignment. */
export function MetricCard({ label, value, unit, hint, tone = 'idle', trend, className }: MetricCardProps) {
  return (
    <div
      className={clsx(
        'rounded-lg border border-hairline-subtle bg-surface-1 p-4 transition-all duration-base ease-out hover:border-hairline-strong hover:shadow-card',
        className,
      )}
    >
      <p className="label-caps">{label}</p>
      <p className="mt-2 flex items-baseline gap-1.5">
        <span className={clsx('font-mono text-2xl font-semibold tabular-nums', VALUE_TEXT[tone])}>{value}</span>
        {unit ? <span className="font-mono text-xs text-ink-muted">{unit}</span> : null}
        {trend ? (
          <span aria-hidden="true" className={clsx('ml-1 text-xs', TREND_TEXT[trend])}>
            {trend === 'up' ? '▲' : trend === 'down' ? '▼' : '─'}
          </span>
        ) : null}
        {trend ? (
          <span className="sr-only">{trend === 'up' ? 'Sube' : trend === 'down' ? 'Baja' : 'Estable'}</span>
        ) : null}
      </p>
      {hint ? <p className="mt-1.5 text-xs text-ink-muted">{hint}</p> : null}
    </div>
  )
}