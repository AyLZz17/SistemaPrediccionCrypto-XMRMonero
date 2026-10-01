import clsx from 'clsx'
import type { ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'active'

const TONES: Record<BadgeTone, string> = {
  neutral: 'border-hairline-default bg-surface-2 text-ink-secondary',
  info: 'border-accent-cyan/30 bg-accent-cyan-soft text-accent-cyan',
  success: 'border-accent-green/30 bg-accent-green-soft text-accent-green',
  warning: 'border-accent-amber/30 bg-accent-amber-soft text-accent-amber',
  danger: 'border-accent-red/30 bg-accent-red-soft text-accent-red',
  active: 'border-accent-cyan/40 bg-accent-cyan-soft text-accent-cyan',
}

export interface BadgeProps {
  tone?: BadgeTone
  children: ReactNode
  className?: string
  /** Adds a small leading dot (used for live status). */
  dot?: boolean
  pulse?: boolean
  title?: string
}

export function Badge({ tone = 'neutral', children, className, dot = false, pulse = false, title }: BadgeProps) {
  return (
    <span
      title={title}
      className={clsx(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 font-mono text-xs uppercase tracking-wide',
        TONES[tone],
        className,
      )}
    >
      {dot ? (
        <span
          aria-hidden="true"
          className={clsx('h-1.5 w-1.5 rounded-full bg-current', pulse && 'animate-pulse')}
        />
      ) : null}
      {children}
    </span>
  )
}