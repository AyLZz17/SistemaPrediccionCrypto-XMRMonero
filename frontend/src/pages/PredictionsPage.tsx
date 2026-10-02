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

function directionText(direction: string | null | undefined): string {
  if (direction === 'UP') return 'Sube'
  if (direction === 'DOWN') return 'Baja'
  if (direction === 'FLAT') return 'Estable'
  return '—'
}

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
    { key: 'targetDate', header: 'Fecha objetivo', render: (row) => <span className="font-mono tabular-nums">{row.targetDate}</span> },
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
      header: 'Dirección',
      render: (row) =>
        row.predictedDirection ? (
          <span className="inline-flex items-center gap-2">
            <StatusPill status={row.predictedDirection} />
            <span className="text-[13px] text-ink-secondary">{directionText(row.predictedDirection)}</span>
          </span>
        ) : (
          <span className="text-ink-muted">—</span>
        ),
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
      render: (row) => <span className="font-mono text-xs tabular-nums">{formatDateTime(row.createdAt)}</span>,
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
    <div className="space-y-5">
      <div className="border-b border-hairline-subtle pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="label-caps-ticked">Pronósticos · estimación estadística</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Predicciones</h1>
            <p className="mt-1 text-[13px] text-ink-secondary">
              Cierre del día siguiente y dirección (sube / baja) generados por los modelos registrados.
              Ningún pronóstico es una recomendación de inversión.
            </p>
          </div>
          {canRequest ? (
            <Button onClick={() => setModalOpen(true)}>Nueva predicción</Button>
          ) : (
            <span className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">Rol ANALYST requerido para generar</span>
          )}
        </div>
      </div>

      <Disclaimer variant="full" />

      <div className="grid items-start gap-5 xl:grid-cols-3">
        {/* ------------------------------------------------------ historial */}
        <Panel tone="raised" flush className="xl:col-span-2">
          <div className="p-5 pb-3">
            <PanelHeader title="Historial de predicciones" subtitle="Pronóstico, resultado y fecha objetivo por fila" />
          </div>
          {predictions.isPending ? (
            <div className="p-5 pt-0"><div className="skeleton-bar h-64 w-full min-w-0" role="status" aria-label="Cargando predicciones" /></div>
          ) : predictions.isError ? (
            <div className="p-5 pt-0">
              <ErrorState
                message={toApiError(predictions.error).friendlyMessage}
                requestId={toApiError(predictions.error).requestId}
                onRetry={() => void predictions.refetch()}
              />
            </div>
          ) : predictions.data && predictions.data.items.length > 0 ? (
            <>
              <DataTable
                caption="Historial de predicciones"
                columns={columns}
                rows={predictions.data.items}
                rowKey={(row) => row.id}
              />
              <div className="px-1 pb-1">
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
            <div className="p-5 pt-0">
              <EmptyState
                title="Sin predicciones registradas"
                description={
                  canRequest
                    ? 'Genera la primera predicción para un modelo y una fecha objetivo.'
                    : 'Tu rol solo permite consultar. Un ANALYST puede generar predicciones.'
                }
                action={
                  canRequest ? (
                    <Button size="sm" onClick={() => setModalOpen(true)}>
                      Generar predicción
                    </Button>
                  ) : undefined
                }
              />
            </div>
          )}
        </Panel>

        {/* --------------------------------------------------- carril lateral */}
        <div className="space-y-5">
          <Panel>
            <PanelHeader title="Generar pronóstico" subtitle="Solo ANALYST · se encola en el backend" marker="none" />
            <p className="text-[13px] leading-normal text-ink-secondary">
              El cálculo se encola como trabajo y el servicio ML se invoca solo a través del backend.
              El resultado queda registrado con su modelo y su fecha objetivo.
            </p>
            <div className="mt-4">
              {canRequest ? (
                <Button fullWidth onClick={() => setModalOpen(true)}>
                  Nueva predicción
                </Button>
              ) : (
                <p className="rounded-sm border border-hairline-subtle bg-surface-inset px-3 py-2.5 text-xs leading-normal text-ink-muted">
                  Tu rol solo permite consultar el historial. Solicita acceso ANALYST para generar pronósticos.
                </p>
              )}
            </div>
          </Panel>

          <Panel>
            <PanelHeader title="Cómo leer estas cifras" subtitle="Notas metodológicas" marker="none" />
            <ul className="space-y-2.5 text-[13px] leading-normal text-ink-secondary">
              <li className="flex gap-2.5">
                <span className="font-mono text-[11px] tabular-nums text-brand-strong">01</span>
                <span>
                  Cada muestra pertenece al subconjunto de la <strong className="font-semibold text-ink">fecha de su
                  objetivo</strong>; la ventana de entrada solo mira hacia atrás.
                </span>
              </li>
              <li className="flex gap-2.5">
                <span className="font-mono text-[11px] tabular-nums text-brand-strong">02</span>
                <span>
                  Los valores se des-escalan a USD antes de calcular MAE, RMSE y MAPE.
                </span>
              </li>
              <li className="flex gap-2.5">
                <span className="font-mono text-[11px] tabular-nums text-brand-strong">03</span>
                <span>
                  Un pronóstico negativo también se publica: no se ajustan parámetros usando la prueba para
                  mejorar el resultado.
                </span>
              </li>
            </ul>
          </Panel>
        </div>
      </div>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Generar predicción"
        description="El backend encola el cálculo; el servicio ML se invoca solo a través del backend."
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
            hint="Se predecirá el cierre de ese día usando solo información anterior."
          />
          <Notice>
            Esta acción no ejecuta operaciones ni simula rentabilidad. Solo registra un pronóstico de
            capacidad predictiva evaluada.
          </Notice>
          <p className="font-mono text-xs text-ink-muted">
            Fecha objetivo: {formatDate(targetDate)}
          </p>
        </form>
      </Modal>
    </div>
  )
}
