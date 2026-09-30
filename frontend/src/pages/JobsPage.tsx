import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cancelJob, fetchJobs } from '../api'
import { toApiError } from '../api/errors'
import { ActivityBar, Button, EmptyState, ErrorState, Modal, Notice, Panel, PanelHeader, SkeletonTable, StatusDot } from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { StatusPill } from '../components/common/StatusPill'
import { formatDateTime } from '../utils/format'
import type { Job } from '../types'

export default function JobsPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [target, setTarget] = useState<Job | null>(null)

  const jobs = useQuery({
    queryKey: ['jobs', page],
    queryFn: () => fetchJobs(page, 15),
    // Poll while something is in flight so the monitor feels alive.
    refetchInterval: (query) => {
      const items = query.state.data?.items ?? []
      return items.some((job: Job) => job.status === 'RUNNING' || job.status === 'QUEUED') ? 5000 : false
    },
    retry: 1,
  })

  const cancelMutation = useMutation({
    mutationFn: (id: string) => cancelJob(id),
    onSuccess: async () => {
      setTarget(null)
      await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    },
  })

  const columns: Array<Column<Job>> = [
    { key: 'id', header: 'id', render: (row) => <span className="font-mono text-xs">{row.id.slice(0, 8)}</span> },
    { key: 'type', header: 'Tipo', render: (row) => <span className="font-mono text-xs">{row.type}</span> },
    { key: 'status', header: 'Estado', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'progress',
      header: 'Progreso',
      render: (row) => (
        <div className="min-w-32">
          <ActivityBar
            label={row.status}
            tone={row.status === 'FAILED' ? 'danger' : row.status === 'SUCCEEDED' ? 'success' : 'active'}
            progress={row.progress ?? (row.status === 'SUCCEEDED' ? 1 : row.status === 'FAILED' ? 1 : 0)}
          />
        </div>
      ),
    },
    {
      key: 'createdAt',
      header: 'Creada',
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.createdAt)}</span>,
    },
    {
      key: 'actions',
      header: 'Acciones',
      render: (row) =>
        row.status === 'RUNNING' || row.status === 'QUEUED' ? (
          <Button size="sm" variant="danger" onClick={() => setTarget(row)}>
            Cancelar
          </Button>
        ) : (
          <span className="font-mono text-xs text-ink-muted">—</span>
        ),
    },
  ]

  const activeCount = (jobs.data?.items ?? []).filter(
    (job) => job.status === 'RUNNING' || job.status === 'QUEUED',
  ).length

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="label-caps">Cola</p>
          <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Monitor de tareas</h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Ingesta, entrenamiento y pronostico encolados por el backend. El frontend solo observa y cancela.
          </p>
        </div>
        <StatusDot
          tone={activeCount > 0 ? 'active' : 'idle'}
          label={`${activeCount} en curso`}
          pulse={activeCount > 0}
        />
      </div>

      <Panel tone="raised">
        <PanelHeader
          title="Tareas"
          subtitle={jobs.isFetching ? 'Actualizando...' : 'Actualizacion automatica mientras haya actividad'}
        />
        {jobs.isPending ? (
          <SkeletonTable rows={5} columns={5} />
        ) : jobs.isError ? (
          <ErrorState
            message={toApiError(jobs.error).friendlyMessage}
            requestId={toApiError(jobs.error).requestId}
            onRetry={() => void jobs.refetch()}
          />
        ) : jobs.data && jobs.data.items.length > 0 ? (
          <>
            <DataTable caption="Cola de tareas" columns={columns} rows={jobs.data.items} rowKey={(row) => row.id} />
            <div className="mt-4">
              <Pagination
                page={jobs.data.page}
                totalPages={jobs.data.totalPages}
                total={jobs.data.total}
                size={jobs.data.size}
                onPageChange={setPage}
                disabled={jobs.isFetching}
              />
            </div>
          </>
        ) : (
          <EmptyState
            title="La cola esta vacia"
            description="No hay tareas pendientes ni en ejecucion. Lanza una ingesta o un entrenamiento desde la seccion de experimentos."
          />
        )}
      </Panel>

      <Modal
        open={target !== null}
        onClose={() => setTarget(null)}
        title="Cancelar tarea"
        description="La tarea se detendra en el backend. Los artefactos parciales se conservan para trazabilidad."
        dismissible
        footer={
          <>
            <Button variant="ghost" onClick={() => setTarget(null)}>
              Mantener
            </Button>
            <Button
              variant="danger"
              loading={cancelMutation.isPending}
              onClick={() => {
                if (target) cancelMutation.mutate(target.id)
              }}
            >
              Cancelar tarea
            </Button>
          </>
        }
      >
        {cancelMutation.isError ? (
          <ErrorState
            message={toApiError(cancelMutation.error).friendlyMessage}
            requestId={toApiError(cancelMutation.error).requestId}
            onRetry={() => cancelMutation.reset()}
          />
        ) : (
          <Notice>
            Vas a cancelar la tarea <span className="font-mono">{target?.id}</span> de tipo{' '}
            <span className="font-mono">{target?.type}</span>.
          </Notice>
        )}
      </Modal>
    </div>
  )
}
