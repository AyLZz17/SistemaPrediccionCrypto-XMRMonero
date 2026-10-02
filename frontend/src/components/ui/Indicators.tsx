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

const TREND_TEXT: Record<'up' | 'down' | 'flat', string> = {
  up: 'text-success',
  down: 'text-danger',
  flat: 'text-ink-muted',
}

const TREND_GLYPH: Record<'up' | 'down' | 'flat', string> = {
  up: '▲',
  down: '▼',
  flat: '—',
}

export interface StatusDotProps {
  tone?: IndicatorTone
  /** Live dots animate to convey ongoing activity. */
  pulse?: boolean
  label?: string
  className?: string
  'data-testid'?: string
}

/** Small non-color-only status cue: square marker + text label. */
export function StatusDot({ tone = 'idle', pulse = false, label, className, ...rest }: StatusDotProps) {
  return (
    <span
      {...rest}
      className={clsx('inline-flex items-center gap-2', TEXT[tone], className)}
    >
      <span
        aria-hidden="true"
        className={clsx('relative inline-flex h-1.5 w-1.5', DOT[tone], pulse && 'animate-pulse')}
      />
      {label ? <span className="font-mono text-[11px] uppercase tracking-wide">{label}</span> : null}
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
          <span className="font-mono text-[11px] tabular-nums text-ink-secondary">
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
        className="relative h-1 w-full overflow-hidden bg-surface-inset"
      >
        {pct === undefined ? (
          <span
            aria-hidden="true"
            className="absolute inset-y-0 left-0 w-1/3 bg-active/70 animate-pulse"
          />
        ) : (
          <span
            aria-hidden="true"
            className={clsx('block h-full transition-all duration-slow ease-out', DOT[tone])}
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

/**
 * Ledger stat: open composition, no box. Caps label, tabular value in warm
 * ink, delta as glyph + word (never colour alone), muted hint line.
 */
export function MetricCard({ label, value, unit, hint, tone = 'idle', trend, className }: MetricCardProps) {
  void tone
  return (
    <div className={clsx('border-t-2 border-hairline-strong pt-2.5', className)}>
      <p className="label-caps">{label}</p>
      <p className="mt-1 flex flex-wrap items-baseline gap-x-2">
        <span className="font-mono text-[26px] font-semibold tabular-nums leading-none text-ink">{value}</span>
        {unit ? <span className="font-mono text-xs text-ink-muted">{unit}</span> : null}
      </p>
      <p className="mt-1 flex flex-wrap items-center gap-x-2 text-xs">
        {trend ? (
          <span className={clsx('font-mono tabular-nums', TREND_TEXT[trend])}>
            <span aria-hidden="true">{TREND_GLYPH[trend]} </span>
            <span>{trend === 'up' ? 'sube' : trend === 'down' ? 'baja' : 'estable'}</span>
            <span className="sr-only">{trend === 'up' ? 'Sube' : trend === 'down' ? 'Baja' : 'Estable'}</span>
          </span>
        ) : null}
        {hint ? <span className="text-ink-muted">{hint}</span> : null}
      </p>
    </div>
  )
}
