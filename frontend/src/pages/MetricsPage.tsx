import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchExperiments } from '../api/experiments'
import { fetchExperimentMetrics } from '../api'
import { toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { hasAtLeast } from '../types'
import {
  EmptyState,
  ErrorState,
  MetricCard,
  Notice,
  Panel,
  PanelHeader,
  SelectField,
  SkeletonStat,
  StatusDot,
} from '../components/ui'
import { Disclaimer } from '../components/common/Disclaimer'
import { formatNumber } from '../utils/format'

export default function MetricsPage() {
  const user = useAuthStore((state) => state.user)
  const canPromote = user ? hasAtLeast(user.role, 'ADMIN') : false
  const [experimentId, setExperimentId] = useState('')

  const experiments = useQuery({
    queryKey: ['experiments', 0],
    queryFn: () => fetchExperiments(0, 50),
    retry: 1,
  })

  const metrics = useQuery({
    queryKey: ['metrics', experimentId],
    queryFn: () => fetchExperimentMetrics(experimentId),
    enabled: experimentId !== '',
    retry: 1,
  })

  const test = metrics.data?.test
  const validation = metrics.data?.validation

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Evaluacion</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Metricas</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          MAE, RMSE, MAPE y proporcion de aciertos de direccion. El campeon se elige con validacion; la
          prueba se reporta una sola vez (R-24).
        </p>
      </div>

      <Disclaimer variant="full" />

      <Panel tone="raised">
        <PanelHeader title="Seleccionar experimento" subtitle="Las metricas se piden al backend por id" />
        <div className="max-w-md">
          <SelectField
            label="Experimento"
            name="experimentId"
            value={experimentId}
            onChange={(event) => setExperimentId(event.target.value)}
          >
            <option value="">{experiments.isPending ? 'Cargando experimentos...' : 'Selecciona un experimento'}</option>
            {(experiments.data?.items ?? []).map((experiment) => (
              <option key={experiment.id} value={experiment.id}>
                {experiment.name} ({experiment.task})
              </option>
            ))}
          </SelectField>
        </div>
        {experiments.isError ? (
          <div className="mt-4">
            <ErrorState
              message={toApiError(experiments.error).friendlyMessage}
              requestId={toApiError(experiments.error).requestId}
              onRetry={() => void experiments.refetch()}
            />
          </div>
        ) : null}
        {canPromote ? (
          <div className="mt-4 flex items-center gap-3">
            <StatusDot tone="danger" label="Rol ADMIN: puedes promover modelos" />
          </div>
        ) : null}
      </Panel>

      {!experimentId ? (
        <EmptyState
          title="Selecciona un experimento"
          description="Las metricas de MAE, RMSE, MAPE y direccion apareceran aqui, separadas en validacion y prueba."
        />
      ) : metrics.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <SkeletonStat />
          <SkeletonStat />
          <SkeletonStat />
          <SkeletonStat />
        </div>
      ) : metrics.isError ? (
        <ErrorState
          message={toApiError(metrics.error).friendlyMessage}
          requestId={toApiError(metrics.error).requestId}
          onRetry={() => void metrics.refetch()}
        />
      ) : (
        <>
          <section aria-label="Metricas de validacion" className="space-y-3">
            <div className="flex items-center gap-3">
              <h2 className="text-lg font-semibold text-ink">Validacion</h2>
              <StatusDot tone="active" label="usada para elegir el campeon" />
            </div>
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="MAE" value={formatNumber(validation?.mae ?? metrics.data?.mae, 4)} unit="USD" tone="active" />
              <MetricCard label="RMSE" value={formatNumber(validation?.rmse ?? metrics.data?.rmse, 4)} unit="USD" tone="active" />
              <MetricCard label="MAPE" value={formatNumber(validation?.mape ?? metrics.data?.mape, 4)} unit="%" tone="warning" />
              <MetricCard
                label="Direccion"
                value={formatNumber(
                  (validation?.directionAccuracy ?? metrics.data?.directionAccuracy ?? 0) * 100,
                  2,
                )}
                unit="%"
                tone="success"
                hint="Aciertos sube / baja"
              />
            </div>
          </section>

          <section aria-label="Metricas de prueba" className="space-y-3">
            <div className="flex items-center gap-3">
              <h2 className="text-lg font-semibold text-ink">Prueba (evaluacion final)</h2>
              <StatusDot tone="warning" label="uso unico" />
            </div>
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="MAE" value={formatNumber(test?.mae, 4)} unit="USD" tone="active" />
              <MetricCard label="RMSE" value={formatNumber(test?.rmse, 4)} unit="USD" tone="active" />
              <MetricCard label="MAPE" value={formatNumber(test?.mape, 4)} unit="%" tone="warning" />
              <MetricCard
                label="Direccion"
                value={formatNumber((test?.directionAccuracy ?? 0) * 100, 2)}
                unit="%"
                tone="success"
              />
            </div>
          </section>

          <Notice>
            Si el modelo recurrente no supera a las lineas base, ese resultado se publica tal cual. El
            analisis de cambios bruscos y picos suavizados forma parte del informe.
          </Notice>
        </>
      )}
    </div>
  )
}
