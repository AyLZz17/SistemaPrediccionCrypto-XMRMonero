import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { register } from '../api/auth'
import { toApiError, type FieldError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { ApiErrorAlert, Button, Notice, Panel, TextField } from '../components/ui'
import { GoogleButton } from '../components/auth/GoogleButton'
import { Disclaimer } from '../components/common/Disclaimer'

interface RegisterErrors {
  fullName?: string
  email?: string
  password?: string
  confirmPassword?: string
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const setStatus = useAuthStore((state) => state.setStatus)
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState<RegisterErrors>({})
  const [error, setError] = useState<unknown>(null)
  const [done, setDone] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)

    const nextErrors: RegisterErrors = {}
    if (fullName.trim().length < 2) nextErrors.fullName = 'Indica tu nombre completo.'
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) nextErrors.email = 'Introduce un correo valido.'
    if (password.length < 12) nextErrors.password = 'La contrasena debe tener al menos 12 caracteres.'
    if (password !== confirm) nextErrors.confirmPassword = 'Las contrasenas no coinciden.'
    setFieldErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return

    setSubmitting(true)
    try {
      await register({ email: email.trim(), password, fullName: fullName.trim() })
      setDone(true)
      // Registration does not return a token in this contract: send to login.
      setStatus('anonymous')
      navigate('/login', { replace: true, state: { justRegistered: true } })
    } catch (caught) {
      const apiError = toApiError(caught)
      const mapped: RegisterErrors = {}
      for (const fieldError of apiError.fieldErrors as FieldError[]) {
        if (fieldError.field === 'email') mapped.email = fieldError.message
        if (fieldError.field === 'password') mapped.password = fieldError.message
        if (fieldError.field === 'fullName') mapped.fullName = fieldError.message
      }
      setFieldErrors(mapped)
      setError(apiError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto w-full max-w-md space-y-6">
      <div className="text-center">
        <h1 className="text-2xl font-semibold text-ink">Crear cuenta</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Accede a la capacidad predictiva evaluada de XMR-Forecast. Empezaras con el rol VIEWER.
        </p>
      </div>

      {error ? <ApiErrorAlert error={error} /> : null}
      {done ? (
        <Notice>Cuenta creada correctamente. Ya puedes iniciar sesion con tu correo.</Notice>
      ) : null}

      <Panel>
        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <TextField
            label="Nombre completo"
            name="fullName"
            autoComplete="name"
            required
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
            error={fieldErrors.fullName}
            hint="Tal y como aparecera en los informes exportados."
          />
          <TextField
            label="Correo electronico"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={fieldErrors.email}
          />
          <TextField
            label="Contrasena"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={fieldErrors.password}
            hint="Minimo 12 caracteres. Recomendamos un gestor de contrasenas."
          />
          <TextField
            label="Repetir contrasena"
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            error={fieldErrors.confirmPassword}
          />
          <Button type="submit" loading={submitting} fullWidth>
            Crear cuenta
          </Button>
        </form>

        <div className="my-5 flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-hairline" />
          <span className="label-caps">o</span>
          <span className="h-px flex-1 bg-hairline" />
        </div>

        <GoogleButton returnTo="/dashboard" label="Continuar con Google" />

        <p className="mt-5 text-center text-xs text-ink-secondary">
          Ya tienes cuenta?{' '}
          <Link to="/login" className="link-accent">
            Inicia sesion
          </Link>
        </p>
      </Panel>

      <Disclaimer variant="short" />
    </div>
  )
}
