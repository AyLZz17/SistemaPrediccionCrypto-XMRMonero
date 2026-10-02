import clsx from 'clsx'
import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  description?: ReactNode
  children?: ReactNode
  footer?: ReactNode
  /** Blocks backdrop / Escape dismissal for destructive confirmations. */
  dismissible?: boolean
  size?: 'sm' | 'md' | 'lg'
}

const SIZES = {
  sm: 'max-w-md',
  md: 'max-w-xl',
  lg: 'max-w-3xl',
} as const

export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  dismissible = true,
  size = 'md',
}: ModalProps) {
  const panelRef = useRef<HTMLDivElement | null>(null)
  const previouslyFocused = useRef<Element | null>(null)

  useEffect(() => {
    if (!open) return undefined
    previouslyFocused.current = document.activeElement
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && dismissible) {
        event.stopPropagation()
        onClose()
      }
      if (event.key === 'Tab' && panelRef.current) {
        // Focus trap (WCAG 2.1.2 / no keyboard trap).
        const focusables = panelRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
        )
        if (focusables.length === 0) return
        const first = focusables[0]!
        const last = focusables[focusables.length - 1]!
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    const timer = window.setTimeout(() => {
      panelRef.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    }, 0)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      window.clearTimeout(timer)
      if (previouslyFocused.current instanceof HTMLElement) previouslyFocused.current.focus()
    }
  }, [open, dismissible, onClose])

  if (!open) return null
  if (typeof document === 'undefined') return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="absolute inset-0 animate-fade-in-slow bg-surface-overlay"
        onClick={dismissible ? onClose : undefined}
        aria-hidden="true"
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        aria-describedby={description ? 'modal-description' : undefined}
        className={clsx(
          'relative max-h-[90vh] w-full animate-fade-in overflow-y-auto rounded-md border border-hairline-default bg-surface-modal p-5 shadow-card-lg',
          SIZES[size],
        )}
      >
        <span aria-hidden="true" className="absolute inset-x-0 top-0 h-0.5 bg-brand" />
        <div className="mb-4 flex items-start justify-between gap-4">
          <div className="flex min-w-0 items-start gap-2.5">
            <span aria-hidden="true" className="mt-1 h-4 w-0.5 shrink-0 bg-brand" />
            <div className="min-w-0">
              <h2 id="modal-title" className="text-base font-semibold text-ink">
                {title}
              </h2>
              {description ? (
                <p id="modal-description" className="mt-1 text-[13px] text-ink-secondary">
                  {description}
                </p>
              ) : null}
            </div>
          </div>
          {dismissible ? (
            <button
              type="button"
              onClick={onClose}
              aria-label="Cerrar dialogo"
              className="shrink-0 rounded-sm border border-hairline p-1.5 text-ink-muted transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
            >
              <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" strokeLinecap="round" />
              </svg>
            </button>
          ) : null}
        </div>
        {children}
        {footer ? <div className="mt-5 flex flex-wrap justify-end gap-2 border-t border-hairline-subtle pt-4">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  )
}
