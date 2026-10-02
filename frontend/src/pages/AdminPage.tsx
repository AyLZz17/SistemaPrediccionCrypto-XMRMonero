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
          <p className="font-medium text-ink">{row.fullName}</p>
          <p className="font-mono text-xs text-ink-muted">{row.email}</p>
        </div>
      ),
    },
    {
      key: 'role',
      header: 'Rol actual',
      render: (row) => (
        <span className={`font-mono text-xs uppercase tracking-wide ${row.role === 'ADMIN' ? 'text-accent-red' : 'text-ink-secondary'}`}>
          {ROLE_LABELS[row.role]}
        </span>
      ),
    },
    {
      key: 'lastLoginAt',
      header: 'Último acceso',
      render: (row) => <span className="font-mono text-xs tabular-nums">{formatDateTime(row.lastLoginAt)}</span>,
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
            className="rounded-sm border border-hairline bg-surface-2 px-2 py-1.5 font-mono text-xs text-ink transition-colors duration-fast hover:border-hairline-strong disabled:opacity-50"
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
    <div className="space-y-5">
      <div className="border-b border-hairline-subtle pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="label-caps-ticked">Administración · herramienta interna</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Usuarios y roles</h1>
            <p className="mt-1 text-[13px] text-ink-secondary">
              Gestión de privilegios. El backend valida cada cambio y lo registra en la auditoría.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <StatusDot tone="danger" label="Sesión ADMIN" />
            <StatusDot tone="idle" label={`${adminCount} administradores en esta página`} />
          </div>
        </div>
      </div>

      {error ? <ApiRoleError error={error} onDismiss={() => setError(null)} /> : null}

      <Panel tone="raised" flush>
        <div className="p-5 pb-3">
          <PanelHeader title="Cuentas registradas" subtitle="Selecciona un rol para aplicarlo de inmediato" />
        </div>
        {users.isPending ? (
          <div className="p-5 pt-0"><SkeletonTable rows={5} columns={4} /></div>
        ) : users.isError ? (
          <div className="p-5 pt-0">
            <ErrorState
              message={toApiError(users.error).friendlyMessage}
              requestId={toApiError(users.error).requestId}
              onRetry={() => void users.refetch()}
            />
          </div>
        ) : users.data && users.data.items.length > 0 ? (
          <>
            <DataTable caption="Usuarios de la plataforma" columns={columns} rows={users.data.items} rowKey={(row) => row.id} dense />
            <div className="px-1 pb-1">
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
          <div className="p-5 pt-0">
            <EmptyState title="Sin usuarios" description="El backend no devolvió ninguna cuenta." />
          </div>
        )}
      </Panel>

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel>
          <PanelHeader title="Modelo de permisos" marker="none" />
          <dl className="divide-y divide-hairline-subtle border-t border-hairline-subtle">
            {ROLES.map((role) => (
              <div key={role} className="flex items-baseline gap-3 py-2.5">
                <dt className="min-w-28 font-mono text-xs uppercase tracking-wide text-ink">{ROLE_LABELS[role]}</dt>
                <dd className="text-[13px] leading-normal text-ink-secondary">
                  {role === 'ADMIN'
                    ? 'Control total, incluida la promoción de modelos y la auditoría.'
                    : role === 'ANALYST'
                      ? 'Puede crear experimentos, lanzar corridas y consultar métricas.'
                      : 'Solo lectura sobre mercado, predicciones y monitor de tareas.'}
                </dd>
              </div>
            ))}
          </dl>
        </Panel>

        <Panel>
          <PanelHeader title="Buenas prácticas" subtitle="Controles de seguridad aplicados" marker="none" />
          <ul className="space-y-2.5 text-[13px] leading-normal text-ink-secondary">
            <li className="flex gap-2.5">
              <span className="font-mono text-[11px] tabular-nums text-brand-strong">01</span>
              <span>Principio de mínimo privilegio: concede solo el rol que la persona necesita.</span>
            </li>
            <li className="flex gap-2.5">
              <span className="font-mono text-[11px] tabular-nums text-brand-strong">02</span>
              <span>Los cambios de rol quedan registrados en la auditoría con su requestId.</span>
            </li>
            <li className="flex gap-2.5">
              <span className="font-mono text-[11px] tabular-nums text-brand-strong">03</span>
              <span>Las cuentas ADMIN deben usar MFA en el proveedor de identidad.</span>
            </li>
          </ul>
          <div className="mt-4">
            <Notice>
              El ADMIN accede a estas rutas; el resto de roles reciben 403 del backend si las invocan
              directamente. La interfaz solo oculta lo que no corresponde.
            </Notice>
          </div>
        </Panel>
      </div>
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
