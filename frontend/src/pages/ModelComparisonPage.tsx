import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchExperiments, fetchModels, fetchModelVersions, promoteModelVersion } from '../api/experiments'
import { fetchMetricComparison } from '../api'
import { toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { hasAtLeast, type ModelDescriptor, type ModelVersion } from '../types'
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  MetricCard,
  Modal,
  Notice,
  Panel,
  PanelHeader,
  SelectField,
  SkeletonTable,
  StatusDot,
} from '../components/ui'
import { MetricComparisonChart } from '../components/charts/MetricComparisonChart'
import { formatDateTime, formatNumber } from '../utils/format'

const BASELINES = ['media movil', 'regresion lineal', 'ARIMA']

interface Promotion {
  model: ModelDescriptor
  version: ModelVersion
}

/** Owns its own query so the rules of hooks are respected inside a list. */
function ModelVersionsCard({
  model,
  isAdmin,
  onPromote,
}: {
  model: ModelDescriptor
  isAdmin: boolean
  onPromote: (promotion: Promotion) => void
}) {
  const versions = useQuery({
    queryKey: ['models', model.id, 'versions'],
    queryFn: () => fetchModelVersions(model.id),
    retry: 1,
  })

  return (
    <li className="rounded-md border border-hairline-subtle p-3">
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-sm text-ink">{model.name}</span>
        <Badge tone="neutral">{model.family}</Badge>
        <span className="font-mono text-[11px] text-ink-muted">{model.id}</span>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        {versions.isPending ? (
          <span className="font-mono text-xs text-ink-muted">cargando versiones...</span>
        ) : versions.isError ? (
          <span className="font-mono text-xs text-accent-red">
            {toApiError(versions.error).friendlyMessage}
          </span>
        ) : (versions.data ?? []).length === 0 ? (
          <span className="font-mono text-xs text-ink-muted">sin versiones</span>
        ) : (
          (versions.data ?? []).map((version) => (
            <span key={version.id} className="flex items-center gap-2">
              <Badge tone={version.isChampion ? 'success' : 'neutral'}>v{version.version}</Badge>
              {isAdmin && !version.isChampion ? (
                <Button size="sm" variant="ghost" onClick={() => onPromote({ model, version })}>
                  Promover
                </Button>
              ) : null}
            </span>
          ))
        )}
      </div>
    </li>
  )
}

export default function ModelComparisonPage() {
  const user = useAuthStore((state) => state.user)
  const isAdmin = user ? hasAtLeast(user.role, 'ADMIN') : false
  const queryClient = useQueryClient()

  const [experimentId, setExperimentId] = useState('')
  const [promotion, setPromotion] = useState<Promotion | null>(null)

  const experiments = useQuery({
    queryKey: ['experiments', 0],
    queryFn: () => fetchExperiments(0, 50),
    retry: 1,
  })

  const models = useQuery({
    queryKey: ['models', 0],
    queryFn: () => fetchModels(0, 20),
    retry: 1,
  })

  const comparison = useQuery({
    queryKey: ['metrics', 'compare', experimentId],
    queryFn: () => fetchMetricComparison(experimentId),
    enabled: experimentId !== '',
    retry: 1,
  })

  const promoteMutation = useMutation({
    mutationFn: (target: Promotion) => promoteModelVersion(target.model.id, target.version.id),
    onSuccess: async () => {
      setPromotion(null)
      await queryClient.invalidateQueries({ queryKey: ['models'] })
    },
  })

  const best = comparison.data
    ? comparison.data.reduce<{ label: string; mae: number } | null>((acc, row) => {
        const mae = row.metrics.mae
        if (typeof mae !== 'number') return acc
        if (!acc || mae < acc.mae) return { label: row.modelName ?? row.label, mae }
        return acc
      }, null)
    : null

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Comparacion</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Modelos frente a lineas base</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          LSTM y GRU evaluados contra {BASELINES.join(', ')} sobre exactamente la misma particion y las mismas
          fechas (R-05).
        </p>
      </div>

      <Panel tone="raised">
        <PanelHeader title="Experimento de comparacion" />
        <div className="max-w-md">
          <SelectField
            label="Experimento"
            name="experimentId"
            value={experimentId}
            onChange={(event) => setExperimentId(event.target.value)}
          >
            <option value="">{experiments.isPending ? 'Cargando...' : 'Selecciona un experimento'}</option>
            {(experiments.data?.items ?? []).map((experiment) => (
              <option key={experiment.id} value={experiment.id}>
                {experiment.name}
              </option>
            ))}
          </SelectField>
        </div>
      </Panel>

      {!experimentId ? (
        <EmptyState
          title="Selecciona un experimento"
          description="Veras MAE, RMSE, MAPE y aciertos de direccion por modelo, incluido el campeon vigente."
        />
      ) : comparison.isPending ? (
        <Panel tone="raised">
          <PanelHeader title="Cargando comparativa" />
          <SkeletonTable rows={4} columns={5} />
        </Panel>
      ) : comparison.isError ? (
        <ErrorState
          message={toApiError(comparison.error).friendlyMessage}
          requestId={toApiError(comparison.error).requestId}
          onRetry={() => void comparison.refetch()}
        />
      ) : comparison.data && comparison.data.length > 0 ? (
        <>
          <section aria-label="Resumen de la comparativa" className="grid gap-4 sm:grid-cols-3">
            <MetricCard
              label="Modelos evaluados"
              value={comparison.data.length}
              tone="active"
              hint="Sobre la misma particion"
            />
            <MetricCard
              label="Menor MAE"
              value={best ? formatNumber(best.mae, 4) : '—'}
              unit={best ? 'USD' : undefined}
              tone="success"
              hint={best?.label}
            />
            <MetricCard
              label="Campeon"
              value={comparison.data.find((row) => row.isChampion)?.modelName ?? '—'}
              tone="idle"
              hint="Elegido por validacion"
            />
          </section>

          <Panel tone="raised">
            <PanelHeader title="Metricas por modelo" subtitle="Menor es mejor en MAE, RMSE y MAPE" />
            <MetricComparisonChart rows={comparison.data} />
            <div className="mt-2 flex flex-wrap items-center gap-3">
              <StatusDot tone="active" label="Validacion decide el campeon" />
              <StatusDot tone="warning" label="Prueba: uso unico" />
            </div>
          </Panel>

          <Panel tone="raised">
            <PanelHeader title="Detalle" subtitle="Direccion de acierto y desviacion entre semillas" />
            <ul className="divide-y divide-hairline-subtle">
              {comparison.data.map((row) => (
                <li key={row.label} className="flex flex-wrap items-center gap-3 py-2.5">
                  <span className="text-sm text-ink">{row.modelName ?? row.label}</span>
                  {row.family ? <Badge tone="neutral">{row.family}</Badge> : null}
                  {row.isChampion ? (
                    <Badge tone="success" dot>
                      campeon
                    </Badge>
                  ) : null}
                  <span className="ml-auto font-mono text-xs tabular-nums text-ink-secondary">
                    dir {formatNumber((row.metrics.directionAccuracy ?? 0) * 100, 2)}% · sd{' '}
                    {formatNumber(row.metrics.meanStdDev, 4)}
                  </span>
                </li>
              ))}
            </ul>
          </Panel>
        </>
      ) : (
        <EmptyState
          title="Sin comparativa para este experimento"
          description="El backend no devolvio filas. Comprueba que el experimento tenga corridas finalizadas."
        />
      )}

      <Panel tone="raised">
        <PanelHeader title="Versiones por modelo" subtitle="Campeon vigente y promocion" />
        {models.isPending ? (
          <SkeletonTable rows={3} columns={2} />
        ) : models.isError ? (
          <ErrorState
            message={toApiError(models.error).friendlyMessage}
            requestId={toApiError(models.error).requestId}
            onRetry={() => void models.refetch()}
          />
        ) : models.data && models.data.items.length > 0 ? (
          <ul className="space-y-2">
            {models.data.items.map((model) => (
              <ModelVersionsCard
                key={model.id}
                model={model}
                isAdmin={isAdmin}
                onPromote={setPromotion}
              />
            ))}
          </ul>
        ) : (
          <EmptyState title="No hay modelos registrados" />
        )}
        <div className="mt-4">
          <Notice>
            {isAdmin
              ? 'Como ADMIN puedes promover una version a campeon. El backend revalida integridad, procedencia y no-fuga de datos antes de aceptar la promocion.'
              : 'La promocion de campeon esta reservada al rol ADMIN. Tu rol puede consultar la comparativa.'}
          </Notice>
        </div>
      </Panel>

      <Modal
        open={promotion !== null}
        onClose={() => setPromotion(null)}
        title="Promover version a campeon"
        description="Esta accion cambia el modelo que la plataforma usa como referencia."
        footer={
          <>
            <Button variant="ghost" onClick={() => setPromotion(null)}>
              Cancelar
            </Button>
            <Button
              loading={promoteMutation.isPending}
              onClick={() => {
                if (promotion) promoteMutation.mutate(promotion)
              }}
            >
              Promover
            </Button>
          </>
        }
      >
        {promoteMutation.isError ? (
          <ErrorState
            message={toApiError(promoteMutation.error).friendlyMessage}
            requestId={toApiError(promoteMutation.error).requestId}
            onRetry={() => promoteMutation.reset()}
          />
        ) : (
          <p className="text-sm text-ink-secondary">
            Se promovera la version{' '}
            <span className="font-mono text-ink">{promotion?.version.version}</span> del modelo{' '}
            <span className="font-mono text-ink">{promotion?.model.name}</span>, creada el{' '}
            {formatDateTime(promotion?.version.createdAt)}.
          </p>
        )}
      </Modal>
    </div>
  )
}
