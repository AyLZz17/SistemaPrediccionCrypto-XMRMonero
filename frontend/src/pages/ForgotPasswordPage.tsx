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
    <div className="mx-auto w-full max-w-md">
      <Panel tone="strong">
        <div className="mb-5 flex items-center gap-3 border-b-2 border-hairline-strong pb-4">
          <BrandMark size={34} />
          <div>
            <p className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">XMR-Forecast · recuperación</p>
            <h1 className="mt-0.5 text-xl font-semibold tracking-tight text-ink">Recuperar contrasena</h1>
          </div>
        </div>
        <p className="mb-4 text-[13px] leading-normal text-ink-secondary">
          Te enviaremos un enlace de un solo uso. El enlace caduca por seguridad.
        </p>

        {error ? <div className="mb-4"><ApiErrorAlert error={error} /></div> : null}
        {sent ? (
          <div className="mb-4">
            <Notice>
              Si la cuenta existe, recibirás un correo con las instrucciones. Por seguridad no revelamos si el
              correo esta registrado.
            </Notice>
          </div>
        ) : null}

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
        <p className="mt-5 border-t border-hairline-subtle pt-4 text-center font-mono text-[11px] uppercase tracking-wide">
          <Link to="/reset-password" className="link-accent">
            Ya tengo un token de recuperacion
          </Link>
        </p>
      </Panel>
    </div>
  )
}
