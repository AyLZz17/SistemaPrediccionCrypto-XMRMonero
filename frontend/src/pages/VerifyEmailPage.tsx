import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { verifyEmail } from '../api/auth'
import { ApiError, toApiError } from '../api/errors'
import { Button, Panel, StatusDot } from '../components/ui'

export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const [error, setError] = useState<ApiError | null>(null)
  const [verified, setVerified] = useState(false)

  useEffect(() => {
    const token = params.get('token')
    if (!token) {
      setError(new ApiError({ kind: 'http', status: 400, code: 'MISSING_VERIFICATION_TOKEN', friendlyMessage: 'El enlace de confirmacion no es valido.' }))
      return
    }
    void verifyEmail(token).then(() => setVerified(true)).catch((caught) => setError(toApiError(caught)))
  }, [params])

  return (
    <div className="mx-auto w-full max-w-md">
      <Panel>
        <StatusDot tone={verified ? 'success' : error ? 'danger' : 'active'} label={verified ? 'Correo confirmado' : error ? 'No se pudo confirmar' : 'Confirmando correo'} pulse={!verified && !error} />
        <h1 className="mt-4 text-xl font-semibold text-ink">{verified ? 'Cuenta confirmada' : 'Confirmar correo'}</h1>
        <p className="mt-2 text-sm text-ink-secondary">
          {verified ? 'Ya puedes iniciar sesion con tu correo y contrasena.' : error?.friendlyMessage ?? 'Estamos activando tu cuenta.'}
        </p>
        <Link to="/login" className="mt-5 inline-block"><Button>{verified ? 'Iniciar sesion' : 'Volver al inicio'}</Button></Link>
      </Panel>
    </div>
  )
}