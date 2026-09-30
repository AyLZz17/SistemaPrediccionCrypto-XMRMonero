import clsx from 'clsx'
import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react'
import { useId } from 'react'

const FIELD_BASE =
  'w-full rounded-md border border-hairline bg-surface-inset px-3 py-2.5 text-sm text-ink placeholder:text-ink-muted transition-all duration-fast ease-out hover:border-hairline-strong focus:border-accent-cyan/60 disabled:cursor-not-allowed disabled:opacity-50'

function FieldShell({
  id,
  label,
  hint,
  error,
  required,
  children,
  className,
}: {
  id: string
  label: string
  hint?: ReactNode
  error?: string | null
  required?: boolean
  children: ReactNode
  className?: string
}) {
  return (
    <div className={clsx('space-y-1.5', className)}>
      <label htmlFor={id} className="flex items-center gap-1 text-sm font-medium text-ink-secondary">
        {label}
        {required ? (
          <span aria-hidden="true" className="text-accent-red">
            *
          </span>
        ) : null}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-accent-red">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="text-xs text-ink-muted">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string
  hint?: ReactNode
  error?: string | null
  containerClassName?: string
}

export function TextField({ label, hint, error, required, className, containerClassName, ...rest }: TextFieldProps) {
  const autoId = useId()
  const id = rest.name ? `${rest.name}-${autoId}` : autoId
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} required={required} className={containerClassName}>
      <input
        id={id}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={clsx(FIELD_BASE, error && 'border-accent-red/70', className)}
        {...rest}
      />
    </FieldShell>
  )
}

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: string
  hint?: ReactNode
  error?: string | null
  containerClassName?: string
  children: ReactNode
}

export function SelectField({
  label,
  hint,
  error,
  required,
  className,
  containerClassName,
  children,
  ...rest
}: SelectFieldProps) {
  const autoId = useId()
  const id = rest.name ? `${rest.name}-${autoId}` : autoId
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} required={required} className={containerClassName}>
      <select
        id={id}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        className={clsx(FIELD_BASE, 'appearance-none pr-8', error && 'border-accent-red/70', className)}
        {...rest}
      >
        {children}
      </select>
    </FieldShell>
  )
}

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: string
  hint?: ReactNode
  error?: string | null
  containerClassName?: string
}

export function TextAreaField({
  label,
  hint,
  error,
  required,
  className,
  containerClassName,
  ...rest
}: TextAreaFieldProps) {
  const autoId = useId()
  const id = rest.name ? `${rest.name}-${autoId}` : autoId
  return (
    <FieldShell id={id} label={label} hint={hint} error={error} required={required} className={containerClassName}>
      <textarea
        id={id}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        className={clsx(FIELD_BASE, 'min-h-24 resize-y font-mono', error && 'border-accent-red/70', className)}
        {...rest}
      />
    </FieldShell>
  )
}
