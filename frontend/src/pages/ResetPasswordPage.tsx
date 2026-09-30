import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../api/auth'
import { toApiError } from '../api/errors'
import { ApiErrorAlert, Button, Notice, Panel, TextField } from '../components/ui'

export default function ResetPasswordPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const tokenFromUrl = params.get('token') ?? ''
  const [token, setToken] = useState(tokenFromUrl)
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    setFieldError(null)
    if (!token.trim()) {
      setFieldError('Introduce el token que recibiste por correo.')
      return
    }
    if (password.length < 12) {
      setFieldError('La contrasena debe tener al menos 12 caracteres.')
      return
    }
    if (password !== confirm) {
      setFieldError('Las contrasenas no coinciden.')
      return
    }
    setSubmitting(true)
    try {
      await resetPassword({ token: token.trim(), newPassword: password })
      navigate('/login', { replace: true, state: { passwordReset: true } })
    } catch (caught) {
      setError(toApiError(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto w-full max-w-md space-y-6">
      <div className="text-center">
        <h1 className="text-2xl font-semibold text-ink">Establecer nueva contrasena</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          El token de recuperacion es de un solo uso y caduca tras un tiempo limitado.
        </p>
      </div>

      {error ? <ApiErrorAlert error={error} /> : null}
      {!tokenFromUrl ? (
        <Notice>
          Abre el enlace que te enviamos por correo: el token llega en la direccion. Si no lo tienes,{' '}
          <Link to="/forgot-password" className="link-accent">
            solicita uno nuevo
          </Link>
          .
        </Notice>
      ) : null}

      <Panel>
        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <TextField
            label="Token de recuperacion"
            name="token"
            required
            value={token}
            onChange={(event) => setToken(event.target.value)}
            className="font-mono"
            hint="Cadena alfanumerica enviada por correo."
          />
          <TextField
            label="Nueva contrasena"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <TextField
            label="Repetir nueva contrasena"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            error={fieldError}
          />
          <Button type="submit" loading={submitting} fullWidth>
            Guardar nueva contrasena
          </Button>
        </form>
      </Panel>
    </div>
  )
}
