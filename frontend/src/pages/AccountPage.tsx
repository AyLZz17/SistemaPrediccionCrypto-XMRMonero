import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { changePassword, logout, me } from '../api/auth'
import { toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { hasAtLeast, ROLE_LABELS, type User } from '../types'
import { Alert, Badge, Button, Notice, Panel, PanelHeader, TextField } from '../components/ui'
import { readRefreshToken } from '../auth/tokenStorage'
import { formatDateTime } from '../utils/format'
import { AnalystAccessRequestForm } from '../components/analyst/AnalystAccessRequestForm'

export default function AccountPage() {
  const user = useAuthStore((state) => state.user)
  const clearSession = useAuthStore((state) => state.clearSession)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const changeMutation = useMutation({
    mutationFn: () => changePassword({ currentPassword, newPassword }),
    onSuccess: () => {
      setCurrentPassword('')
      setNewPassword('')
      setConfirm('')
      setFormError(null)
      setDone(true)
    },
    onError: (error) => setFormError(toApiError(error).friendlyMessage),
  })

  const refreshProfile = useMutation({
    mutationFn: me,
    onSuccess: (fresh: User) => useAuthStore.getState().setUser(fresh),
  })

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setDone(false)
    setFormError(null)
    if (newPassword.length < 12) {
      setFormError('La nueva contrasena debe tener al menos 12 caracteres.')
      return
    }
    if (newPassword !== confirm) {
      setFormError('Las contrasenas no coinciden.')
      return
    }
    changeMutation.mutate()
  }

  /**
   * Logout revokes the refresh token server-side (204) before the local session
   * is dropped, so a stolen cookie cannot be replayed (R-27).
   */
  const handleLogout = async () => {
    try {
      await logout(readRefreshToken() ? { refreshToken: readRefreshToken() as string } : {})
    } catch {
      /* even if revocation fails locally we drop the session */
    }
    clearSession('Sesion cerrada correctamente.')
    window.location.assign('/login')
  }

  if (!user) {
    return (
      <Notice>
        No hay sesion activa. <a className="link-accent" href="/login">Inicia sesion</a> para ver tu cuenta.
      </Notice>
    )
  }

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Cuenta</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Mi cuenta</h1>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel tone="raised">
          <PanelHeader
            title="Perfil"
            subtitle="Datos gestionados por el backend"
            actions={
              <Button size="sm" variant="secondary" loading={refreshProfile.isPending} onClick={() => refreshProfile.mutate()}>
                Actualizar
              </Button>
            }
          />
          <dl className="space-y-3 text-sm">
            <div className="flex items-center justify-between gap-3">
              <dt className="text-ink-secondary">Nombre completo</dt>
              <dd className="text-ink">{user.fullName}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-ink-secondary">Correo</dt>
              <dd className="font-mono text-xs text-ink">{user.email}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-ink-secondary">Rol</dt>
              <dd>
                <Badge tone={hasAtLeast(user.role, 'ADMIN') ? 'danger' : hasAtLeast(user.role, 'ANALYST') ? 'active' : 'neutral'}>
                  {ROLE_LABELS[user.role]}
                </Badge>
              </dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-ink-secondary">Identificador</dt>
              <dd className="font-mono text-xs text-ink-muted">{user.id}</dd>
            </div>
            {user.lastLoginAt ? (
              <div className="flex items-center justify-between gap-3">
                <dt className="text-ink-secondary">Ultimo acceso</dt>
                <dd className="font-mono text-xs text-ink">{formatDateTime(user.lastLoginAt)}</dd>
              </div>
            ) : null}
          </dl>
          <div className="mt-5 border-t border-hairline-subtle pt-4">
            <Button variant="danger" onClick={() => void handleLogout()}>
              Cerrar sesion y revocar token
            </Button>
          </div>
        </Panel>

        <Panel tone="raised">
          <PanelHeader title="Cambiar contrasena" subtitle="Minimo 12 caracteres" />
          {done ? <Alert tone="success">Contrasena actualizada correctamente.</Alert> : null}
          <form onSubmit={handleSubmit} noValidate className="mt-4 space-y-4">
            {formError ? <Alert tone="danger">{formError}</Alert> : null}
            <TextField
              label="Contrasena actual"
              name="currentPassword"
              type="password"
              autoComplete="current-password"
              required
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
            <TextField
              label="Nueva contrasena"
              name="newPassword"
              type="password"
              autoComplete="new-password"
              required
              minLength={12}
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            />
            <TextField
              label="Repetir nueva contrasena"
              name="confirmPassword"
              type="password"
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
            />
            <Button type="submit" loading={changeMutation.isPending}>
              Actualizar contrasena
            </Button>
          </form>
          <div className="mt-4">
            <Notice>
              Al cambiar la contrasena, las sesiones activas del backend se revocan. Este navegador volvera a
              pedir un token de acceso.
            </Notice>
          </div>
        </Panel>
      </div>

      {!hasAtLeast(user.role, 'ANALYST') ? (
        <AnalystAccessRequestForm />
      ) : null}
    </div>
  )
}
