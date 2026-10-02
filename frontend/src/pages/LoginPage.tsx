import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { login, me } from '../api/auth'
import { useAuthStore } from '../store/authStore'
import { toApiError } from '../api/errors'
import { ApiErrorAlert, Button, Notice, Panel, TextField } from '../components/ui'
import { GoogleButton } from '../components/auth/GoogleButton'
import { Disclaimer } from '../components/common/Disclaimer'
import { BrandMark } from '../components/layout/BrandMark'

interface LocationState {
  from?: { pathname?: string; search?: string; hash?: string }
}

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const applyTokenResponse = useAuthStore((state) => state.applyTokenResponse)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [submitting, setSubmitting] = useState(false)

  const from = (location.state as LocationState | null)?.from?.pathname ?? '/dashboard'

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const response = await login({ email: email.trim(), password })
      applyTokenResponse(response)
      // Confirm identity so the role gate has authoritative data.
      try {
        const user = await me()
        useAuthStore.getState().setUser(user)
      } catch {
        /* the token response already carries the user */
      }
      navigate(from, { replace: true })
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
            <p className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">XMR-Forecast · acceso</p>
            <h1 className="mt-0.5 text-xl font-semibold tracking-tight text-ink">Iniciar sesión</h1>
          </div>
        </div>
        <p className="mb-4 text-[13px] leading-normal text-ink-secondary">
          Accede al panel de XMR-Forecast. La sesión caduca sola y se renueva de forma silenciosa.
        </p>

        {error ? <div className="mb-4"><ApiErrorAlert error={error} onRetry={() => setError(null)} /></div> : null}

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
          <TextField
            label="Contrasena"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="••••••••"
          />
          <Button type="submit" loading={submitting} fullWidth>
            Entrar
          </Button>
        </form>

        <div className="my-5 flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-hairline" />
          <span className="label-caps">o</span>
          <span className="h-px flex-1 bg-hairline" />
        </div>

        <GoogleButton returnTo={from} label="Continuar con Google" />

        <div className="mt-5 flex flex-wrap justify-between gap-2 border-t border-hairline-subtle pt-4 font-mono text-[11px] uppercase tracking-wide">
          <Link to="/forgot-password" className="link-accent">
            Olvide mi contrasena
          </Link>
          <Link to="/register" className="link-accent">
            Crear una cuenta
          </Link>
        </div>
      </Panel>

      <div className="mt-4">
        <Notice>
          El botón de Google delega todo el flujo OAuth en el backend (Authorization Code + OIDC). Este frontend
          nunca almacena un client secret de Google.
        </Notice>
      </div>

      <div className="mt-4">
        <Disclaimer variant="short" />
      </div>
    </div>
  )
}
