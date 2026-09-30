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
  primary:
    'border-accent-cyan/50 bg-accent-cyan-soft text-accent-cyan shadow-glow-cyan hover:bg-accent-cyan/20 hover:border-accent-cyan/70',
  secondary:
    'border-hairline bg-surface-2 text-ink hover:border-hairline-strong hover:bg-surface-3',
  ghost: 'border-transparent bg-transparent text-ink-secondary hover:text-ink hover:bg-surface-2',
  danger: 'border-accent-red/50 bg-accent-red-soft text-accent-red hover:bg-accent-red/20 hover:border-accent-red/70',
}

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
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
        'relative inline-flex items-center justify-center gap-2 rounded-md border font-medium',
        'transition-all duration-fast ease-out',
        'disabled:cursor-not-allowed disabled:opacity-45 disabled:shadow-none',
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
