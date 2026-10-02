import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { forgotPassword } from '../api/auth'
import { toApiError } from '../api/errors'
import { ApiErrorAlert, Button, Notice, Panel, TextField } from '../components/ui'
import { BrandMark } from '../components/layout/BrandMark'

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [sent, setSent] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await forgotPassword({ email: email.trim() })
      setSent(true)
    } catch (caught) {
      setError(toApiError(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto w-full max-w-md space-y-5">
      <div className="flex flex-col items-center gap-3 text-center">
        <BrandMark size={36} />
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink">Recuperar contrasena</h1>
          <p className="mt-1 text-sm leading-normal text-ink-secondary">
            Te enviaremos un enlace de un solo uso. El enlace caduca por seguridad.
          </p>
        </div>
      </div>

      {error ? <ApiErrorAlert error={error} /> : null}
      {sent ? (
        <Notice>
          Si la cuenta existe, recibirás un correo con las instrucciones. Por seguridad no revelamos si el
          correo esta registrado.
        </Notice>
      ) : null}

      <Panel tone="raised">
        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <TextField
            label="Correo electronico"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="analista@ejemplo.com"
          />
          <Button type="submit" loading={submitting} fullWidth>
            Enviar enlace
          </Button>
        </form>
        <p className="mt-5 text-center text-xs text-ink-secondary">
          <Link to="/reset-password" className="link-accent">
            Ya tengo un token de recuperacion
          </Link>
        </p>
      </Panel>
    </div>
  )
}
