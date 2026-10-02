import clsx from 'clsx'
import type { HTMLAttributes, ReactNode } from 'react'

export type PanelTone = 'default' | 'strong' | 'well' | 'line' | 'danger'

const TONES: Record<PanelTone, string> = {
  default: 'console-box',
  strong: 'console-box-strong shadow-card',
  well: 'console-well',
  /** Border-only strip: transparent body for grouped ledger rows. */
  line: 'border border-hairline-subtle bg-transparent',
  danger: 'console-box-strong border-accent-red/40',
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
        'rounded transition-colors duration-base ease-out',
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
  as?: 'h1' | 'h2' | 'h3' | 'h4'
  id?: string
}

/**
 * Box header: title row with a hairline rule underneath. Ledger pages use
 * `<Section>` instead; this header belongs to boxes (forms, dialogs, cards).
 */
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
    <div className={clsx('mb-4 border-b border-hairline-subtle pb-3', className)}>
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-2.5">
          {icon ? (
            <span aria-hidden="true" className="mt-0.5 shrink-0 text-ink-secondary">
              {icon}
            </span>
          ) : null}
          <div className="min-w-0">
            <Heading id={id} className="text-[15px] font-semibold leading-tight text-ink">
              {title}
            </Heading>
            {subtitle ? <p className="mt-0.5 text-[13px] leading-normal text-ink-secondary">{subtitle}</p> : null}
          </div>
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
    </div>
  )
}
