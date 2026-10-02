import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { register } from '../api/auth'
import { toApiError, type FieldError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { ApiErrorAlert, Button, CheckboxField, Notice, Panel, TextField } from '../components/ui'
import { GoogleButton } from '../components/auth/GoogleButton'
import { Disclaimer } from '../components/common/Disclaimer'
import { BrandMark } from '../components/layout/BrandMark'

interface RegisterErrors {
  fullName?: string
  email?: string
  password?: string
  confirmPassword?: string
  consent?: string
}

export default function RegisterPage() {
  const navigate = useNavigate()
  const setStatus = useAuthStore((state) => state.setStatus)
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [acceptTerms, setAcceptTerms] = useState(false)
  const [acceptDataPolicy, setAcceptDataPolicy] = useState(false)
  const [acceptMarketing, setAcceptMarketing] = useState(false)
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
    if (!acceptTerms) nextErrors.consent = 'Debes aceptar los terminos y condiciones.'
    else if (!acceptDataPolicy)
      nextErrors.consent = 'Debes aceptar la politica de tratamiento de datos personales.'
    setFieldErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return

    setSubmitting(true)
    try {
      await register({
        email: email.trim(),
        password,
        fullName: fullName.trim(),
        // The two mandatory consents travel with the request: the backend
        // rejects the registration without them and records the version.
        acceptTerms,
        acceptDataPolicy,
        acceptMarketing,
      })
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
        if (fieldError.field === 'acceptTerms' || fieldError.field === 'acceptDataPolicy')
          mapped.consent = fieldError.message
      }
      setFieldErrors(mapped)
      setError(apiError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto w-full max-w-md space-y-5">
      <div className="flex flex-col items-center gap-3 text-center">
        <BrandMark size={36} />
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-ink">Crear cuenta</h1>
          <p className="mt-1 text-sm leading-normal text-ink-secondary">
            Accede a la capacidad predictiva evaluada de XMR-Forecast. Empezarás con el rol VIEWER.
          </p>
        </div>
      </div>

      {error ? <ApiErrorAlert error={error} /> : null}
      {done ? (
        <Notice>Cuenta creada correctamente. Ya puedes iniciar sesion con tu correo.</Notice>
      ) : null}

      <Panel tone="raised">
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

          {/* Consentimiento: los dos primeros son obligatorios y el servidor los
              vuelve a comprobar; ninguno viene precargado ni se marca solo. */}
          <div
            className="space-y-3 border-t border-hairline-subtle pt-4"
            data-testid="register-consent"
          >
            <p className="label-caps-ticked">Consentimiento</p>
            <CheckboxField
              data-testid="accept-terms"
              name="acceptTerms"
              required
              checked={acceptTerms}
              onChange={(event) => setAcceptTerms(event.target.checked)}
              label={
                <>
                  He leido y acepto los{' '}
                  <Link to="/terms" className="link-accent" data-testid="terms-link">
                    terminos y condiciones
                  </Link>
                  .
                </>
              }
            />
            <CheckboxField
              data-testid="accept-data-policy"
              name="acceptDataPolicy"
              required
              checked={acceptDataPolicy}
              onChange={(event) => setAcceptDataPolicy(event.target.checked)}
              label={
                <>
                  Acepto la{' '}
                  <Link to="/data-policy" className="link-accent" data-testid="data-policy-link">
                    politica de tratamiento de datos personales
                  </Link>
                  .
                </>
              }
            />
            <CheckboxField
              data-testid="accept-marketing"
              name="acceptMarketing"
              checked={acceptMarketing}
              onChange={(event) => setAcceptMarketing(event.target.checked)}
              label="Quiero recibir novedades y comunicaciones comerciales (opcional)."
              hint="Puedes revocarlo en cualquier momento sin que eso afecte tu acceso al servicio."
            />
            {fieldErrors.consent ? (
              <p role="alert" data-testid="consent-error" className="pl-6 text-xs text-accent-red">
                {fieldErrors.consent}
              </p>
            ) : null}
          </div>

          <Button type="submit" loading={submitting} fullWidth>
            Crear cuenta
          </Button>
        </form>

        <div className="my-5 flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-hairline" />
          <span className="label-caps">o</span>
          <span className="h-px flex-1 bg-hairline" />
        </div>

        <GoogleButton
          returnTo="/dashboard"
          label="Continuar con Google"
          consent={{ acceptTerms, acceptDataPolicy, acceptMarketing }}
        />

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
