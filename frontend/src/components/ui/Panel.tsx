import clsx from 'clsx'
import type { HTMLAttributes, ReactNode } from 'react'

export type PanelTone = 'default' | 'raised' | 'elevated' | 'accent'

const TONES: Record<PanelTone, string> = {
  default: 'glass-panel',
  raised: 'glass-panel-strong shadow-card-lg',
  elevated: 'elevated-panel',
  accent: 'glass-panel border-accent-cyan/30 shadow-[0_0_0_1px_rgba(6,182,212,0.2)]',
}

export interface PanelProps extends HTMLAttributes<HTMLDivElement> {
  tone?: PanelTone
  /** Removes the default padding, letting the child own its own layout. */
  flush?: boolean
  as?: 'div' | 'section' | 'article' | 'aside'
}

export function Panel({ tone = 'default', flush = false, as = 'div', className, children, ...rest }: PanelProps) {
  const Tag = as
  return (
    <Tag
      className={clsx(
        'rounded-lg transition-all duration-base ease-out',
        TONES[tone],
        !flush && 'p-5',
        className,
      )}
      {...rest}
    >
      {children}
    </Tag>
  )
}

export interface PanelHeaderProps {
  title: ReactNode
  subtitle?: ReactNode
  icon?: ReactNode
  actions?: ReactNode
  className?: string
  /** Heading level for a correct document outline (accessibility). */
  as?: 'h2' | 'h3' | 'h4'
  id?: string
}

export function PanelHeader({
  title,
  subtitle,
  icon,
  actions,
  className,
  as: Heading = 'h2',
  id,
}: PanelHeaderProps) {
  return (
    <div className={clsx('mb-4 flex items-start justify-between gap-4', className)}>
      <div className="flex min-w-0 items-start gap-3">
        {icon ? (
          <span aria-hidden="true" className="mt-0.5 shrink-0 text-ink-secondary">
            {icon}
          </span>
        ) : null}
        <div className="min-w-0">
          <Heading id={id} className="truncate text-lg font-semibold text-ink">
            {title}
          </Heading>
          {subtitle ? <p className="mt-0.5 text-sm text-ink-secondary">{subtitle}</p> : null}
        </div>
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  )
}