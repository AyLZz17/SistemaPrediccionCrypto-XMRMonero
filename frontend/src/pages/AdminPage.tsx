import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchUsers, updateUserRole } from '../api'
import { toApiError } from '../api/errors'
import { ROLES, ROLE_LABELS, hasAtLeast, type AdminUser, type Role } from '../types'
import {
  Alert,
  Button,
  EmptyState,
  ErrorState,
  Notice,
  Panel,
  PanelHeader,
  SkeletonTable,
  StatusDot,
} from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { formatDateTime } from '../utils/format'

export default function AdminPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [error, setError] = useState<unknown>(null)

  const users = useQuery({
    queryKey: ['users', page],
    queryFn: () => fetchUsers(page, 15),
    retry: 1,
  })

  const roleMutation = useMutation({
    mutationFn: ({ id, role }: { id: string; role: Role }) => updateUserRole(id, role),
    onSuccess: async () => {
      setError(null)
      await queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (caught) => setError(caught),
  })

  const columns: Array<Column<AdminUser>> = [
    {
      key: 'fullName',
      header: 'Usuario',
      render: (row) => (
        <div>
          <p className="text-ink">{row.fullName}</p>
          <p className="font-mono text-xs text-ink-muted">{row.email}</p>
        </div>
      ),
    },
    { key: 'role', header: 'Rol actual', render: (row) => ROLE_LABELS[row.role] },
    {
      key: 'lastLoginAt',
      header: 'Ultimo acceso',
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.lastLoginAt)}</span>,
    },
    {
      key: 'actions',
      header: 'Cambiar rol',
      render: (row) => (
        <div className="flex justify-end">
          <label className="sr-only" htmlFor={`role-${row.id}`}>
            Rol de {row.email}
          </label>
          <select
            id={`role-${row.id}`}
            defaultValue={row.role}
            disabled={roleMutation.isPending}
            onChange={(event) => {
              const nextRole = event.target.value as Role
              if (nextRole === row.role) return
              roleMutation.mutate({ id: row.id, role: nextRole })
            }}
            className="rounded-md border border-hairline bg-surface-2 px-2 py-1.5 font-mono text-xs text-ink transition-colors duration-fast hover:border-hairline-strong disabled:opacity-50"
          >
            {ROLES.map((role) => (
              <option key={role} value={role}>
                {ROLE_LABELS[role]}
              </option>
            ))}
          </select>
        </div>
      ),
    },
  ]

  const adminCount = (users.data?.items ?? []).filter((user) => hasAtLeast(user.role, 'ADMIN')).length

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Administracion</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Usuarios y roles</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Gestion de privilegios. El backend valida cada cambio y lo registra en la auditoria.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <StatusDot tone="danger" label="Sesion ADMIN" />
        <StatusDot tone="idle" label={`${adminCount} administradores en esta pagina`} />
      </div>

      {error ? <ApiRoleError error={error} onDismiss={() => setError(null)} /> : null}

      <Panel tone="raised">
        <PanelHeader title="Cuentas registradas" subtitle="Selecciona un rol para aplicarlo de inmediato" />
        {users.isPending ? (
          <SkeletonTable rows={5} columns={4} />
        ) : users.isError ? (
          <ErrorState
            message={toApiError(users.error).friendlyMessage}
            requestId={toApiError(users.error).requestId}
            onRetry={() => void users.refetch()}
          />
        ) : users.data && users.data.items.length > 0 ? (
          <>
            <DataTable caption="Usuarios de la plataforma" columns={columns} rows={users.data.items} rowKey={(row) => row.id} />
            <div className="mt-4">
              <Pagination
                page={users.data.page}
                totalPages={users.data.totalPages}
                total={users.data.total}
                size={users.data.size}
                onPageChange={setPage}
                disabled={users.isFetching}
              />
            </div>
          </>
        ) : (
          <EmptyState title="Sin usuarios" description="El backend no devolvio ninguna cuenta." />
        )}
      </Panel>

      <Panel tone="raised">
        <PanelHeader title="Modelo de permisos" />
        <div className="grid gap-3 sm:grid-cols-3">
          {ROLES.map((role) => (
            <div key={role} className="rounded-md border border-hairline-subtle p-3">
              <p className="font-mono text-sm text-accent-cyan">{ROLE_LABELS[role]}</p>
              <p className="mt-1 text-xs text-ink-secondary">
                {role === 'ADMIN'
                  ? 'Control total, incluida la promocion de modelos y la auditoria.'
                  : role === 'ANALYST'
                    ? 'Puede crear experimentos, lanzar corridas y consultar metricas.'
                    : 'Solo lectura sobre mercado, predicciones y monitor de tareas.'}
              </p>
            </div>
          ))}
        </div>
        <div className="mt-4">
          <Notice>
            El ADMIN accede a estas rutas; el resto de roles reciben 403 del backend si las invocan
            directamente. La interfaz solo oculta lo que no corresponde.
          </Notice>
        </div>
      </Panel>

      <RoleChangePolicy />
    </div>
  )
}

function ApiRoleError({ error, onDismiss }: { error: unknown; onDismiss: () => void }) {
  const apiError = toApiError(error)
  return (
    <Alert
      tone="danger"
      title={apiError.status === 403 ? 'Permiso denegado (403)' : 'No se pudo actualizar el rol'}
      action={
        <Button size="sm" variant="ghost" onClick={onDismiss}>
          Cerrar
        </Button>
      }
    >
      <p>{apiError.friendlyMessage}</p>
      {apiError.requestId ? <p className="mt-1 font-mono text-xs">requestId: {apiError.requestId}</p> : null}
    </Alert>
  )
}

function RoleChangePolicy() {
  return (
    <Panel tone="raised">
      <PanelHeader title="Buenas practicas" subtitle="Controles de seguridad aplicados" />
      <ul className="space-y-2 text-sm text-ink-secondary">
        <li className="flex gap-2">
          <span className="font-mono text-accent-cyan">01</span>
          <span>Principio de minimo privilegio: concede solo el rol que la persona necesita.</span>
        </li>
        <li className="flex gap-2">
          <span className="font-mono text-accent-cyan">02</span>
          <span>Los cambios de rol quedan registrados en la auditoria con su requestId.</span>
        </li>
        <li className="flex gap-2">
          <span className="font-mono text-accent-cyan">03</span>
          <span>Las cuentas ADMIN deben usar MFA en el proveedor de identidad.</span>
        </li>
      </ul>
    </Panel>
  )
}
