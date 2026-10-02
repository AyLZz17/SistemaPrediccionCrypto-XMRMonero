import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import clsx from 'clsx'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md' | 'lg'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  /** Rendered before the label; set `aria-hidden` inside the component. */
  icon?: ReactNode
  fullWidth?: boolean
}

const VARIANTS: Record<ButtonVariant, string> = {
  // Institutional red fill: the single loudest element on any screen.
  primary:
    'border border-brand-deep bg-brand-solid text-ink-inverse shadow-card-sm hover:bg-brand',
  secondary:
    'border border-hairline-default bg-surface-2 text-ink hover:border-hairline-strong hover:bg-surface-3',
  ghost: 'border border-transparent bg-transparent text-ink-secondary hover:text-ink hover:bg-surface-2',
  danger:
    'border border-accent-red/40 bg-accent-red-soft text-accent-red hover:bg-accent-red/15 hover:border-accent-red/60',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-9 px-4 text-sm',
  lg: 'h-11 px-6 text-sm',
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    icon,
    fullWidth = false,
    className,
    children,
    disabled,
    type = 'button',
    ...rest
  },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={clsx(
        'relative inline-flex items-center justify-center gap-2 rounded border font-medium',
        'transition-colors duration-fast ease-out',
        'disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none',
        VARIANTS[variant],
        SIZES[size],
        fullWidth && 'w-full',
        className,
      )}
      {...rest}
    >
      {loading ? (
        <span
          aria-hidden="true"
          className="h-3.5 w-3.5 shrink-0 animate-spin-slow rounded-full border-2 border-current border-t-transparent"
        />
      ) : icon ? (
        <span aria-hidden="true" className="shrink-0">
          {icon}
        </span>
      ) : null}
      <span className="truncate">{children}</span>
    </button>
  )
})
