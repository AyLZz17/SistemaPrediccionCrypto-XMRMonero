import { useEffect, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { bootstrapSession } from '../../auth/session'
import { useAuthStore } from '../../store/authStore'
import { hasAtLeast, type Role } from '../../types'
import { Footer } from '../layout/Footer'
import { StatusDot } from '../ui'
import { ForbiddenPanel } from './ForbiddenPanel'

/**
 * Session gate.
 *
 * While the silent refresh is in flight we render a dark skeleton (never a
 * flash of the login screen) — and the mandatory footer, because this is a
 * visible state of a protected route. Unauthenticated visitors are redirected
 * to `/login` with `state.from` so they land back where they were aiming.
 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status)
  const location = useLocation()

  useEffect(() => {
    if (status === 'unknown') {
      void bootstrapSession()
    }
  }, [status])

  if (status === 'unknown') {
    return (
      <div className="flex min-h-screen flex-col bg-deep">
        <div className="flex flex-1 flex-col items-center justify-center p-6" role="status" aria-busy="true">
          <div className="w-full max-w-md space-y-4">
            <span className="sr-only">Restaurando sesion...</span>
            <div className="flex items-center justify-center gap-3">
              <StatusDot tone="active" label="Restaurando sesion" pulse />
            </div>
          </div>
        </div>
        <Footer />
      </div>
    )
  }

  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <>{children}</>
}

/**
 * Role gate. The backend re-authorises every request (R-27); this only keeps
 * the UI honest and avoids pointless round-trips (R-35). Nested inside the
 * authenticated layout, so the 403 state keeps the mandatory footer.
 */
export function RoleRoute({ minimum, children }: { minimum: Role; children: ReactNode }) {
  const status = useAuthStore((state) => state.status)
  const user = useAuthStore((state) => state.user)
  const location = useLocation()

  useEffect(() => {
    if (status === 'unknown') {
      void bootstrapSession()
    }
  }, [status])

  if (status === 'unknown') {
    return (
      <div className="flex min-h-screen flex-col bg-deep">
        <div className="flex flex-1 flex-col items-center justify-center" role="status" aria-busy="true">
          <span className="sr-only">Verificando permisos...</span>
          <StatusDot tone="active" label="Verificando permisos" pulse />
        </div>
        <Footer />
      </div>
    )
  }

  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  if (!user || !hasAtLeast(user.role, minimum)) {
    return <ForbiddenPanel required={minimum} actual={user?.role ?? null} />
  }

  return <>{children}</>
}

/** Keeps already-authenticated users away from /login, /register, ... */
export function GuestOnlyRoute({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status)
  if (status === 'authenticated') {
    return <Navigate to="/dashboard" replace />
  }
  return <>{children}</>
}
