import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { resendVerification, verifyEmail } from '../api/auth'
import { toApiError, ApiError } from '../api/errors'
import { Button, Notice, Panel, StatusDot, TextField } from '../components/ui'

/**
 * `/verify-email`.
 *
 * Two jobs, in this order:
 *   1. Consume the one-time token carried in the link (POST with `?token=`).
 *   2. When that fails — expired, already used, or the link arrived without a
 *      token — offer the resend form. The resend endpoint always answers 204
 *      whether or not the account exists, so this screen never reveals that a
 *      given address is registered.
 */
export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const [error, setError] = useState<ApiError | null>(null)
  const [verified, setVerified] = useState(false)
  const [verifying, setVerifying] = useState(false)

  const [email, setEmail] = useState('')
  const [resendState, setResendState] = useState<'idle' | 'sending' | 'sent'>('idle')
  const [resendError, setResendError] = useState<string | null>(null)

  useEffect(() => {
    const token = params.get('token')
    if (!token) {
      setError(
        new ApiError({
          kind: 'http',
          status: 400,
          code: 'MISSING_VERIFICATION_TOKEN',
          friendlyMessage: 'El enlace de confirmacion no es valido.',
        }),
      )
      return
    }
    setVerifying(true)
    verifyEmail(token)
      .then(() => setVerified(true))
      .catch((caught) => setError(toApiError(caught)))
      .finally(() => setVerifying(false))
  }, [params])

  const handleResend = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setResendError(null)
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setResendError('Introduce un correo valido.')
      return
    }
    setResendState('sending')
    try {
      await resendVerification({ email: email.trim() })
      setResendState('sent')
    } catch (caught) {
      // Only rate limiting or transport failures land here; the backend does
      // not distinguish existing from unknown addresses.
      setResendError(toApiError(caught).friendlyMessage)
      setResendState('idle')
    }
  }

  const showResendForm = !verified

  return (
    <div className="mx-auto w-full max-w-md">
      <Panel tone="raised">
        <StatusDot
          tone={verified ? 'success' : error ? 'danger' : 'active'}
          label={
            verified
              ? 'Correo confirmado'
              : verifying
                ? 'Comprobando el enlace'
                : 'Confirma tu correo'
          }
          pulse={verifying}
        />

        {verified ? (
          <>
            <h1 className="mt-3 text-xl font-semibold tracking-tight text-ink">Correo confirmado</h1>
            <p className="mt-2 text-sm leading-normal text-ink-secondary">
              Tu direccion ya esta verificada. Ya puedes iniciar sesion con normalidad.
            </p>
            <div className="mt-5">
              <Link to="/login">
                <Button>Iniciar sesión</Button>
              </Link>
            </div>
          </>
        ) : (
          <>
            <h1 className="mt-3 text-xl font-semibold tracking-tight text-ink">
              {verifying ? 'Confirmando tu correo' : 'No pudimos confirmar el correo'}
            </h1>
            {error ? (
              <p className="mt-2 text-sm text-ink-secondary" role="alert" data-testid="verify-error">
                {error.friendlyMessage}
              </p>
            ) : (
              <p className="mt-2 text-sm text-ink-secondary">
                Estamos comprobando el enlace que recibiste por correo.
              </p>
            )}

            {showResendForm ? (
              <div className="mt-5 border-t border-hairline-subtle pt-4" data-testid="resend-block">
                {resendState === 'sent' ? (
                  <Notice>
                    Si existe una cuenta pendiente de confirmacion con ese correo, hemos enviado un
                    enlace nuevo. Revisa tambien la carpeta de spam. El enlace anterior dejo de
                    servir.
                  </Notice>
                ) : (
                  <form onSubmit={handleResend} noValidate className="space-y-3">
                    <p className="text-sm text-ink-secondary">
                      Solicita un correo nuevo y usa el ultimo enlace recibido:
                    </p>
                    <TextField
                      label="Correo electronico"
                      name="email"
                      type="email"
                      autoComplete="email"
                      required
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                      error={resendError}
                      hint="Respondemos igual si el correo no esta registrado."
                    />
                    <Button type="submit" loading={resendState === 'sending'} fullWidth>
                      Reenviar correo de verificacion
                    </Button>
                  </form>
                )}
              </div>
            ) : null}

            <div className="mt-5 flex flex-wrap gap-2 border-t border-hairline-subtle pt-4">
              <Link to="/login">
                <Button variant="secondary">Ir a iniciar sesion</Button>
              </Link>
              <Link to="/register">
                <Button variant="ghost">Crear otra cuenta</Button>
              </Link>
            </div>
          </>
        )}
      </Panel>
    </div>
  )
}
