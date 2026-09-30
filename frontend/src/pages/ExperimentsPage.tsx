import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createExperiment, fetchExperiments, fetchRuns, startRun } from '../api/experiments'
import { toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { hasAtLeast, type Experiment, type ExperimentRun } from '../types'
import {
  Alert,
  Button,
  EmptyState,
  ErrorState,
  Modal,
  Notice,
  Panel,
  PanelHeader,
  SelectField,
  TextAreaField,
  TextField,
} from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { StatusPill } from '../components/common/StatusPill'
import { formatDateTime, formatNumber } from '../utils/format'

const TASK_OPTIONS = [
  { value: 'REGRESSION', label: 'Regresion (cierre t+1)' },
  { value: 'DIRECTION', label: 'Direccion (sube / baja)' },
  { value: 'BOTH', label: 'Ambas tareas' },
]

export default function ExperimentsPage() {
  const user = useAuthStore((state) => state.user)
  const canLaunch = user ? hasAtLeast(user.role, 'ANALYST') : false
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Experiment | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [task, setTask] = useState('BOTH')
  const [formError, setFormError] = useState<string | null>(null)

  const experiments = useQuery({
    queryKey: ['experiments', page],
    queryFn: () => fetchExperiments(page, 15),
    retry: 1,
  })

  const runs = useQuery({
    queryKey: ['runs', selected?.id ?? null],
    queryFn: () => fetchRuns(selected?.id ?? '', 0, 20),
    enabled: Boolean(selected),
    retry: 1,
  })

  const createMutation = useMutation({
    mutationFn: () => createExperiment({ name: name.trim(), description: description.trim(), task: task as Experiment['task'] }),
    onSuccess: async () => {
      setCreateOpen(false)
      setName('')
      setDescription('')
      setFormError(null)
      await queryClient.invalidateQueries({ queryKey: ['experiments'] })
    },
    onError: (error) => setFormError(toApiError(error).friendlyMessage),
  })

  const runMutation = useMutation({
    mutationFn: (experimentId: string) => startRun(experimentId, { seeds: 5 }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['runs'] })
      await queryClient.invalidateQueries({ queryKey: ['experiments'] })
    },
  })

  const experimentColumns: Array<Column<Experiment>> = [
    { key: 'name', header: 'Experimento', render: (row) => <span className="text-ink">{row.name}</span> },
    { key: 'task', header: 'Tarea', render: (row) => <span className="font-mono text-xs">{row.task}</span> },
    { key: 'modelFamily', header: 'Familia', render: (row) => row.modelFamily ?? '—' },
    { key: 'status', header: 'Estado', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'createdAt',
      header: 'Creado',
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.createdAt)}</span>,
    },
    {
      key: 'actions',
      header: 'Acciones',
      render: (row) => (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="ghost" onClick={() => setSelected(row)}>
            Ver corridas
          </Button>
          {canLaunch ? (
            <Button
              size="sm"
              variant="secondary"
              loading={runMutation.isPending && runMutation.variables === row.id}
              onClick={() => runMutation.mutate(row.id)}
            >
              Lanzar
            </Button>
          ) : null}
        </div>
      ),
    },
  ]

  const runColumns: Array<Column<ExperimentRun>> = [
    { key: 'runId', header: 'run_id', render: (row) => <span className="font-mono text-xs">{row.runId}</span> },
    { key: 'status', header: 'Estado', render: (row) => <StatusPill status={row.status} /> },
    { key: 'seed', header: 'Semilla', numeric: true, render: (row) => (row.seed ?? '—') },
    {
      key: 'mae',
      header: 'MAE',
      numeric: true,
      render: (row) => formatNumber(row.metrics?.mae, 4),
    },
    {
      key: 'rmse',
      header: 'RMSE',
      numeric: true,
      render: (row) => formatNumber(row.metrics?.rmse, 4),
    },
    {
      key: 'mape',
      header: 'MAPE (%)',
      numeric: true,
      render: (row) => formatNumber(row.metrics?.mape, 4),
    },
    {
      key: 'startedAt',
      header: 'Inicio',
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.startedAt)}</span>,
    },
  ]

  const handleCreate = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFormError(null)
    if (name.trim().length < 3) {
      setFormError('El nombre debe tener al menos 3 caracteres.')
      return
    }
    createMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="label-caps">Entrenamiento</p>
          <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Experimentos</h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Configuracion y corridas de los modelos. El ajuste de hiperparametros usa solo validacion; la
            prueba se evalua una vez (R-04).
          </p>
        </div>
        {canLaunch ? (
          <Button onClick={() => setCreateOpen(true)}>Nuevo experimento</Button>
        ) : (
          <span className="label-caps">Rol ANALYST requerido para crear</span>
        )}
      </div>

      {runMutation.isError ? (
        <ErrorState
          title="No se pudo lanzar la corrida"
          message={toApiError(runMutation.error).friendlyMessage}
          requestId={toApiError(runMutation.error).requestId}
          onRetry={() => runMutation.reset()}
        />
      ) : null}

      <Panel tone="raised">
        <PanelHeader title="Experimentos registrados" subtitle="Configuraciones de la plataforma" />
        {experiments.isPending ? (
          <div className="skeleton-bar h-64 w-full" role="status" aria-label="Cargando experimentos" />
        ) : experiments.isError ? (
          <ErrorState
            message={toApiError(experiments.error).friendlyMessage}
            requestId={toApiError(experiments.error).requestId}
            onRetry={() => void experiments.refetch()}
          />
        ) : experiments.data && experiments.data.items.length > 0 ? (
          <>
            <DataTable
              caption="Experimentos registrados"
              columns={experimentColumns}
              rows={experiments.data.items}
              rowKey={(row) => row.id}
              isRowActive={(row) => selected?.id === row.id}
            />
            <div className="mt-4">
              <Pagination
                page={experiments.data.page}
                totalPages={experiments.data.totalPages}
                total={experiments.data.total}
                size={experiments.data.size}
                onPageChange={setPage}
                disabled={experiments.isFetching}
              />
            </div>
          </>
        ) : (
          <EmptyState
            title="Sin experimentos"
            description={
              canLaunch
                ? 'Crea el primer experimento para lanzar corridas reproducibles de los modelos.'
                : 'Tu rol solo permite consultar. Un ANALYST puede crear experimentos.'
            }
            action={
              canLaunch ? (
                <Button size="sm" onClick={() => setCreateOpen(true)}>
                  Crear experimento
                </Button>
              ) : undefined
            }
          />
        )}
      </Panel>

      {selected ? (
        <Panel tone="raised">
          <PanelHeader
            title={`Corridas de ${selected.name}`}
            subtitle="Cada fila es una corrida con su semilla y sus metricas"
            actions={
              <Button variant="ghost" size="sm" onClick={() => setSelected(null)}>
                Cerrar
              </Button>
            }
          />
          {runs.isPending ? (
            <div className="skeleton-bar h-48 w-full" role="status" aria-label="Cargando corridas" />
          ) : runs.isError ? (
            <ErrorState
              message={toApiError(runs.error).friendlyMessage}
              requestId={toApiError(runs.error).requestId}
              onRetry={() => void runs.refetch()}
            />
          ) : runs.data && runs.data.items.length > 0 ? (
            <DataTable caption="Corridas del experimento" columns={runColumns} rows={runs.data.items} rowKey={(row) => row.id} dense />
          ) : (
            <EmptyState title="Este experimento aun no tiene corridas" />
          )}
        </Panel>
      ) : null}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Nuevo experimento"
        description="Cada corrida se registra con semilla fija y config versionada."
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" form="experiment-form" loading={createMutation.isPending}>
              Crear
            </Button>
          </>
        }
      >
        <form id="experiment-form" onSubmit={handleCreate} noValidate className="space-y-4">
          {formError ? <Alert tone="danger">{formError}</Alert> : null}
          <TextField
            label="Nombre"
            name="name"
            required
            data-autofocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="lstm_base_w30"
          />
          <SelectField label="Tarea" name="task" value={task} onChange={(event) => setTask(event.target.value)}>
            {TASK_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          <TextAreaField
            label="Descripcion"
            name="description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            hint="Opcional. Se incluye en el informe exportado."
          />
          <Notice>
            El ajuste de hiperparametros se realiza con validacion cronologica (walk-forward). La particion de
            prueba no se consulta hasta la evaluacion final.
          </Notice>
        </form>
      </Modal>
    </div>
  )
}
