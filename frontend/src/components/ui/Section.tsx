import clsx from 'clsx'
import { useId, type ReactNode } from 'react'

export interface SectionProps {
  /** Ledger index shown as a mono folio number ("01", "02", …). */
  index: string
  eyebrow?: ReactNode
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
  /** Test hook preserved across the redesign. */
  'data-testid'?: string
}

/**
 * Ledger section: the base composition unit of every page. A numbered mono
 * eyebrow, a title row and a strong hairline rule, then open content sitting
 * directly on the page — no boxes. Boxes (`Panel`) are reserved for forms,
 * dialogs, menus and terminals.
 *
 * Every section carries an accessible name, so landmark `region`s are always
 * labelled (WCAG) and screen-reader users can jump between them.
 */
export function Section({ index, eyebrow, title, description, actions, children, className, ...rest }: SectionProps) {
  const autoId = useId()
  const headingId = `section-${index}-${autoId.replace(/[^a-zA-Z0-9]/g, '')}`
  return (
    <section aria-labelledby={headingId} className={clsx('scroll-mt-4', className)} {...rest}>
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2 border-b-2 border-hairline-strong pb-2.5">
        <div className="min-w-0">
          <p className="flex items-baseline gap-2 font-mono text-[11px] uppercase tracking-wide">
            <span aria-hidden="true" className="font-semibold tabular-nums text-brand-strong">{index}</span>
            {eyebrow ? <span className="text-ink-muted">{eyebrow}</span> : null}
          </p>
          <h2 id={headingId} className="mt-1 text-xl font-semibold tracking-tight text-ink">
            {title}
          </h2>
          {description ? <p className="mt-1 max-w-3xl text-[13px] leading-normal text-ink-secondary">{description}</p> : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
      <div className="pt-4">{children}</div>
    </section>
  )
}
