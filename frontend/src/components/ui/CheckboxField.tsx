import clsx from 'clsx'
import { useId, type InputHTMLAttributes, type ReactNode } from 'react'

export interface CheckboxFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id'> {
  /** Main text of the option. Keep it short: the explanation goes in `hint`. */
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  containerClassName?: string
}

/**
 * Accessible checkbox with a real `<label>` (clicking the text toggles it),
 * a visible focus ring and an error slot.
 *
 * Used by the registration consent block, where the checkbox is a legal
 * requirement and not decoration: an unlabelled or unreachable input would make
 * the acceptance impossible to demonstrate.
 */
export function CheckboxField({
  label,
  hint,
  error,
  required,
  className,
  containerClassName,
  ...rest
}: CheckboxFieldProps) {
  const autoId = useId()
  const id = rest.name ? `${rest.name}-${autoId}` : autoId
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined

  return (
    <div className={clsx('space-y-1', containerClassName)}>
      <label
        htmlFor={id}
        className={clsx(
          'flex cursor-pointer items-start gap-2.5 rounded-md border border-transparent p-0.5 text-sm text-ink-secondary transition-colors duration-fast hover:text-ink',
          error && 'text-ink',
        )}
      >
        <input
          id={id}
          type="checkbox"
          required={required}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={clsx(
            'mt-0.5 h-4 w-4 shrink-0 cursor-pointer rounded border-hairline bg-surface-inset accent-accent-cyan focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-cyan/60',
            error && 'border-accent-red/70',
            className,
          )}
          {...rest}
        />
        <span className="leading-relaxed">{label}</span>
      </label>
      {error ? (
        <p id={`${id}-error`} role="alert" className="pl-6 text-xs text-accent-red">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="pl-6 text-xs text-ink-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}
