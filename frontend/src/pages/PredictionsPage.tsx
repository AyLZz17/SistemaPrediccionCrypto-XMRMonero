import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createPrediction, fetchPredictions } from '../api'
import { fetchModels } from '../api/experiments'
import { toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { hasAtLeast, type ModelDescriptor } from '../types'
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
  TextField,
} from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { StatusPill } from '../components/common/StatusPill'
import { Disclaimer } from '../components/common/Disclaimer'
import { formatDate, formatDateTime, formatUsd, toIsoDate } from '../utils/format'
import type { Prediction } from '../types'

export default function PredictionsPage() {
  const user = useAuthStore((state) => state.user)
  const canRequest = user ? hasAtLeast(user.role, 'ANALYST') : false
  const queryClient = useQueryClient()

  const [page, setPage] = useState(0)
  const [modalOpen, setModalOpen] = useState(false)
  const [modelId, setModelId] = useState('')
  const [targetDate, setTargetDate] = useState(toIsoDate(new Date()))
  const [formError, setFormError] = useState<string | null>(null)

  const predictions = useQuery({
    queryKey: ['predictions', page],
    queryFn: () => fetchPredictions(page, 15),
    retry: 1,
  })

  const models = useQuery({
    queryKey: ['models', 0],
    queryFn: () => fetchModels(0, 50),
    enabled: canRequest,
    retry: 1,
  })

  const createMutation = useMutation({
    mutationFn: () => createPrediction({ modelId, targetDate }),
    onSuccess: async () => {
      setModalOpen(false)
      setFormError(null)
      await queryClient.invalidateQueries({ queryKey: ['predictions'] })
    },
    onError: (error) => setFormError(toApiError(error).friendlyMessage),
  })

  const columns: Array<Column<Prediction>> = [
    { key: 'targetDate', header: 'Fecha objetivo', render: (row) => <span className="font-mono">{row.targetDate}</span> },
    {
      key: 'modelName',
      header: 'Modelo',
      render: (row) => <span className="text-ink">{row.modelName ?? row.modelId}</span>,
    },
    {
      key: 'predictedClose',
      header: 'Cierre previsto',
      numeric: true,
      render: (row) => formatUsd(row.predictedClose),
    },
    {
      key: 'predictedDirection',
      header: 'Direccion',
      render: (row) =>
        row.predictedDirection ? <StatusPill status={row.predictedDirection} /> : <span className="text-ink-muted">—</span>,
    },
    {
      key: 'actualClose',
      header: 'Cierre real',
      numeric: true,
      render: (row) => formatUsd(row.actualClose ?? undefined),
    },
    { key: 'status', header: 'Estado', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'createdAt',
      header: 'Creada',
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.createdAt)}</span>,
    },
  ]

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFormError(null)
    if (!modelId) {
      setFormError('Selecciona un modelo.')
      return
    }
    createMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="label-caps">Pronosticos</p>
          <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Predicciones</h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Cierre del dia siguiente y direccion (sube / baja) generados por los modelos registrados.
          </p>
        </div>
        {canRequest ? (
          <Button onClick={() => setModalOpen(true)}>Nueva prediccion</Button>
        ) : (
          <span className="label-caps">Rol ANALYST requerido para generar</span>
        )}
      </div>

      <Disclaimer variant="full" />

      <Panel tone="raised">
        <PanelHeader title="Historial de predicciones" subtitle="Pagina actual del endpoint paginado" />
        {predictions.isPending ? (
          <div className="skeleton-bar h-64 w-full" role="status" aria-label="Cargando predicciones" />
        ) : predictions.isError ? (
          <ErrorState
            message={toApiError(predictions.error).friendlyMessage}
            requestId={toApiError(predictions.error).requestId}
            onRetry={() => void predictions.refetch()}
          />
        ) : predictions.data && predictions.data.items.length > 0 ? (
          <>
            <DataTable
              caption="Historial de predicciones"
              columns={columns}
              rows={predictions.data.items}
              rowKey={(row) => row.id}
            />
            <div className="mt-4">
              <Pagination
                page={predictions.data.page}
                totalPages={predictions.data.totalPages}
                total={predictions.data.total}
                size={predictions.data.size}
                onPageChange={setPage}
                disabled={predictions.isFetching}
              />
            </div>
          </>
        ) : (
          <EmptyState
            title="Sin predicciones registradas"
            description={
              canRequest
                ? 'Genera la primera prediccion para un modelo y una fecha objetivo.'
                : 'Tu rol solo permite consultar. Un ANALYST puede generar predicciones.'
            }
            action={
              canRequest ? (
                <Button size="sm" onClick={() => setModalOpen(true)}>
                  Generar prediccion
                </Button>
              ) : undefined
            }
          />
        )}
      </Panel>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Generar prediccion"
        description="El backend encola el calculo; el servicio ML se invoca solo a traves del backend."
        footer={
          <>
            <Button variant="ghost" onClick={() => setModalOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" form="prediction-form" loading={createMutation.isPending}>
              Generar
            </Button>
          </>
        }
      >
        <form id="prediction-form" onSubmit={handleSubmit} noValidate className="space-y-4">
          {formError ? <Alert tone="danger">{formError}</Alert> : null}
          {models.isError ? (
            <ErrorState
              title="No se pudieron cargar los modelos"
              message={toApiError(models.error).friendlyMessage}
              requestId={toApiError(models.error).requestId}
              onRetry={() => void models.refetch()}
            />
          ) : null}
          <SelectField
            label="Modelo"
            name="modelId"
            required
            data-autofocus
            value={modelId}
            onChange={(event) => setModelId(event.target.value)}
            disabled={models.isPending}
          >
            <option value="">{models.isPending ? 'Cargando modelos...' : 'Selecciona un modelo'}</option>
            {(models.data?.items ?? []).map((model: ModelDescriptor) => (
              <option key={model.id} value={model.id}>
                {model.name} ({model.family})
              </option>
            ))}
          </SelectField>
          <TextField
            label="Fecha objetivo"
            name="targetDate"
            type="date"
            required
            value={targetDate}
            onChange={(event) => setTargetDate(event.target.value)}
            hint="Se predecira el cierre de ese dia usando solo informacion anterior."
          />
          <Notice>
            Esta accion no ejecuta operaciones ni simula rentabilidad. Solo registra un pronostico de
            capacidad predictiva evaluada.
          </Notice>
          <p className="font-mono text-xs text-ink-muted">
            Fecha objetivo: {formatDate(targetDate)}
          </p>
        </form>
      </Modal>

      <Panel tone="raised">
        <PanelHeader title="Como leer estas cifras" subtitle="Notas metodologicas" />
        <ul className="space-y-2 text-sm text-ink-secondary">
          <li className="flex gap-2">
            <span className="font-mono text-accent-cyan">01</span>
            <span>
              Cada muestra pertenece al subconjunto de la <strong className="text-ink">fecha de su
              objetivo</strong>; la ventana de entrada solo mira hacia atras.
            </span>
          </li>
          <li className="flex gap-2">
            <span className="font-mono text-accent-cyan">02</span>
            <span>
              Los valores se des-escalan a USD antes de calcular MAE, RMSE y MAPE.
            </span>
          </li>
          <li className="flex gap-2">
            <span className="font-mono text-accent-cyan">03</span>
            <span>
              Un pronostico negativo tambien se publica: no se ajustan parametros usando la prueba para
              mejorar el resultado.
            </span>
          </li>
        </ul>
      </Panel>
    </div>
  )
}
