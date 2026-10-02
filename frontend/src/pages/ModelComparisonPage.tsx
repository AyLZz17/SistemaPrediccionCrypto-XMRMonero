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

const BASELINES = ['media móvil', 'regresión lineal', 'ARIMA']

const BASELINE_FAMILIES = ['MOVING_AVERAGE', 'LINEAR_REGRESSION', 'ARIMA']

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

  const isBaseline = BASELINE_FAMILIES.includes(model.family)

  return (
    <li
      className="rounded-sm border border-hairline-subtle bg-deep p-3.5 hover:border-hairline-default"
      style={isBaseline ? undefined : { boxShadow: 'inset 2px 0 0 var(--xmr-border-strong)' }}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-sm font-semibold text-ink">{model.name}</span>
        <Badge tone="neutral">{model.family}</Badge>
        {isBaseline ? <Badge tone="neutral" title="Referencia clásica, siempre visible">línea base</Badge> : null}
        <span className="ml-auto font-mono text-[11px] tabular-nums text-ink-muted">{model.id}</span>
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-2 border-t border-hairline-subtle pt-2.5">
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
              <Badge tone={version.isChampion ? 'brand' : 'neutral'} title={version.isChampion ? 'Modelo seleccionado por validación' : undefined}>
                v{version.version}{version.isChampion ? ' · campeón' : ''}
              </Badge>
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

  const champion = comparison.data?.find((row) => row.isChampion)

  return (
    <div className="space-y-5">
      <div className="border-b border-hairline-subtle pb-4">
        <p className="label-caps-ticked">Comparación · misma partición, mismas fechas</p>
        <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Modelos frente a líneas base</h1>
        <p className="mt-1 max-w-3xl text-[13px] text-ink-secondary">
          LSTM y GRU evaluados contra {BASELINES.join(', ')} sobre exactamente la misma partición y las mismas
          fechas (R-05).
        </p>
      </div>

      <Panel>
        <PanelHeader title="Experimento de comparación" marker="none" />
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
          description="Verás MAE, RMSE, MAPE y aciertos de dirección por modelo, incluido el campeón vigente."
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
          <section aria-label="Resumen de la comparativa" className="grid gap-x-6 gap-y-5 rounded border border-hairline-subtle bg-deep p-4 sm:grid-cols-3">
            <MetricCard
              label="Modelos evaluados"
              value={comparison.data.length}
              tone="idle"
              hint="Sobre la misma partición"
            />
            <MetricCard
              label="Menor MAE"
              value={best ? formatNumber(best.mae, 4) : '—'}
              unit={best ? 'USD' : undefined}
              tone="idle"
              hint={best?.label}
            />
            <MetricCard
              label="Campeón"
              value={champion?.modelName ?? '—'}
              tone="idle"
              hint="Modelo seleccionado por validación"
            />
          </section>

          <Panel tone="raised">
            <PanelHeader title="Métricas por modelo" subtitle="Menor es mejor en MAE, RMSE y MAPE" />
            <MetricComparisonChart rows={comparison.data} />
            <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-1 border-t border-hairline-subtle pt-2.5">
              <StatusDot tone="idle" label="Validación decide el campeón" />
              <StatusDot tone="warning" label="Prueba: uso único" />
            </div>
          </Panel>

          <Panel tone="raised" flush>
            <div className="p-5 pb-3">
              <PanelHeader title="Detalle por modelo" subtitle="Dirección de acierto y desviación entre semillas" />
            </div>
            <ul className="divide-y divide-hairline-subtle border-t border-hairline-subtle">
              {comparison.data.map((row) => (
                <li
                  key={row.label}
                  className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 hover:bg-surface-2"
                  style={row.isChampion ? { boxShadow: 'inset 2px 0 0 var(--xmr-brand)' } : undefined}
                >
                  <span className="text-sm font-medium text-ink">{row.modelName ?? row.label}</span>
                  {row.family ? <Badge tone="neutral">{row.family}</Badge> : null}
                  {BASELINE_FAMILIES.includes(row.family ?? '') ? <Badge tone="neutral">línea base</Badge> : null}
                  {row.isChampion ? (
                    <Badge tone="brand" title="Modelo seleccionado por validación">
                      campeón
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
          description="El backend no devolvió filas. Comprueba que el experimento tenga corridas finalizadas."
        />
      )}

      <Panel tone="raised">
        <PanelHeader title="Versiones por modelo" subtitle="Campeón vigente y promoción" />
        {models.isPending ? (
          <SkeletonTable rows={3} columns={2} />
        ) : models.isError ? (
          <ErrorState
            message={toApiError(models.error).friendlyMessage}
            requestId={toApiError(models.error).requestId}
            onRetry={() => void models.refetch()}
          />
        ) : models.data && models.data.items.length > 0 ? (
          <ul className="grid gap-3 lg:grid-cols-2">
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
              ? 'Como ADMIN puedes promover una versión a campeón. El backend revalida integridad, procedencia y no-fuga de datos antes de aceptar la promoción.'
              : 'La promoción de campeón está reservada al rol ADMIN. Tu rol puede consultar la comparativa.'}
          </Notice>
        </div>
      </Panel>

      <Modal
        open={promotion !== null}
        onClose={() => setPromotion(null)}
        title="Promover versión a campeón"
        description="Esta acción cambia el modelo que la plataforma usa como referencia."
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
          <p className="text-sm leading-normal text-ink-secondary">
            Se promoverá la versión{' '}
            <span className="font-mono text-[13px] text-ink">{promotion?.version.version}</span> del modelo{' '}
            <span className="font-mono text-[13px] text-ink">{promotion?.model.name}</span>, creada el{' '}
            {formatDateTime(promotion?.version.createdAt)}.
          </p>
        )}
      </Modal>
    </div>
  )
}
