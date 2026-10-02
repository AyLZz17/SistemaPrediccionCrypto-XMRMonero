import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchCandles, fetchLatestQuote } from '../api/market'
import { fetchJobs, fetchPredictions } from '../api'
import { DEFAULT_SYMBOL, hasAtLeast } from '../types'
import { useAuthStore } from '../store/authStore'
import { toApiError } from '../api/errors'
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  MetricCard,
  Panel,
  PanelHeader,
  SkeletonStat,
  SkeletonTable,
  StatusDot,
} from '../components/ui'
import { Disclaimer } from '../components/common/Disclaimer'
import { StatusPill, statusTone } from '../components/common/StatusPill'
import { Sparkline } from '../components/charts/Sparkline'
import { formatUsd, formatDateTime, toIsoDate } from '../utils/format'
import { AnalystAccessRequestForm } from '../components/analyst/AnalystAccessRequestForm'

export default function DashboardPage() {
  const user = useAuthStore((state) => state.user)
  const isAnalyst = user ? hasAtLeast(user.role, 'ANALYST') : false

  const quote = useQuery({
    queryKey: ['market', 'latest', DEFAULT_SYMBOL],
    queryFn: () => fetchLatestQuote(DEFAULT_SYMBOL),
    refetchInterval: 60_000,
    retry: 1,
  })

  const jobs = useQuery({
    queryKey: ['jobs', 0],
    queryFn: () => fetchJobs(0, 5),
    refetchInterval: 30_000,
    retry: 1,
  })

  const predictions = useQuery({
    queryKey: ['predictions', 0],
    queryFn: () => fetchPredictions(0, 5),
    retry: 1,
  })

  const recentCandles = useQuery({
    queryKey: ['market', 'candles', DEFAULT_SYMBOL, 'sparkline'],
    queryFn: () => {
      const to = new Date()
      const from = new Date()
      from.setUTCDate(to.getUTCDate() - 14)
      return fetchCandles({ symbol: DEFAULT_SYMBOL, from: toIsoDate(from), to: toIsoDate(to), page: 0, size: 30 })
    },
    retry: 1,
    staleTime: 300_000,
  })

  const sparkValues = useMemo(
    () => (recentCandles.data?.items ?? []).map((candle) => candle.close),
    [recentCandles.data],
  )

  const runningJobs = jobs.data?.items.filter((job) => job.status === 'RUNNING' || job.status === 'QUEUED') ?? []
  const activeCount = runningJobs.length

  const changePercent = quote.data?.changePercent
  const isUp = typeof changePercent === 'number' && changePercent >= 0

  return (
    <div className="space-y-6">
      {/* ------------------------------------------------------ cabecera */}
      <div className="border-b border-hairline-subtle pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="label-caps-ticked">Centro de operaciones</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">
              Hola, {user?.fullName?.split(' ')[0] ?? 'usuario'}
            </h1>
            <p className="mt-1 text-[13px] text-ink-secondary">
              Estado del sistema, tareas en ejecución y último parte de predicciones.
              {' '}
              <span className="font-mono text-xs text-ink-muted">
                {user?.role}
                {quote.data?.updatedAt ? ` · actualizado ${formatDateTime(quote.data.updatedAt)}` : ''}
              </span>
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <StatusDot
              tone={quote.isError ? 'danger' : quote.isPending ? 'warning' : 'success'}
              label={quote.isError ? 'API sin respuesta' : quote.isPending ? 'Consultando' : 'API operativa'}
              pulse={quote.isFetching}
            />
            <StatusDot tone={activeCount > 0 ? 'active' : 'idle'} label={`${activeCount} tareas activas`} pulse={activeCount > 0} />
          </div>
        </div>
      </div>

      <Disclaimer variant="short" />

      {/* -------------------------------------------------- cinta de KPIs */}
      <section aria-label="Indicadores principales" className="overflow-hidden rounded border border-hairline-subtle bg-deep">
        {quote.isPending ? (
          <div className="grid gap-4 p-4 sm:grid-cols-2 xl:grid-cols-4">
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
          </div>
        ) : quote.isError ? (
          <div className="p-4">
            <ErrorState
              title="No se pudo leer el precio de mercado"
              message={toApiError(quote.error).friendlyMessage}
              requestId={toApiError(quote.error).requestId}
              onRetry={() => void quote.refetch()}
            />
          </div>
        ) : (
          <div className="grid gap-x-6 gap-y-5 p-4 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard
              label="Precio XMR-USD"
              value={formatUsd(quote.data?.price)}
              tone="idle"
              trend={isUp ? 'up' : 'down'}
              hint={quote.data?.source ? `Fuente: ${quote.data.source}` : 'Último cierre disponible'}
            />
            <MetricCard
              label="Variación diaria"
              value={
                typeof quote.data?.changePercent === 'number' ? quote.data.changePercent.toFixed(2) : '—'
              }
              unit="%"
              tone={isUp ? 'success' : 'danger'}
              hint={quote.data?.updatedAt ? `Actualizado ${formatDateTime(quote.data.updatedAt)}` : undefined}
            />
            <MetricCard
              label="Predicciones listadas"
              value={predictions.data?.total ?? 0}
              tone="idle"
              hint="Histórico guardado en el backend"
            />
            <MetricCard
              label="Tareas en curso"
              value={activeCount}
              tone={activeCount > 0 ? 'active' : 'idle'}
              hint="Entrenamiento, ingesta y pronóstico"
            />
          </div>
        )}
      </section>

      {/* --------------------------------------- actividad + plataforma */}
      <div className="grid gap-5 lg:grid-cols-3">
        <Panel tone="raised" className="lg:col-span-2">
          <PanelHeader
            title="Actividad reciente"
            subtitle="Últimas tareas encoladas y en ejecución"
            actions={
              <Link to="/jobs">
                <Button variant="ghost" size="sm">
                  Ver monitor
                </Button>
              </Link>
            }
          />
          {jobs.isPending ? (
            <SkeletonTable rows={4} columns={4} />
          ) : jobs.isError ? (
            <ErrorState
              message={toApiError(jobs.error).friendlyMessage}
              requestId={toApiError(jobs.error).requestId}
              onRetry={() => void jobs.refetch()}
            />
          ) : jobs.data && jobs.data.items.length > 0 ? (
            <ul className="divide-y divide-hairline-subtle border-t border-hairline-subtle">
              {jobs.data.items.map((job) => (
                <li key={job.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2.5">
                  <span className="font-mono text-xs tabular-nums text-ink-muted">{job.id.slice(0, 8)}</span>
                  <span className="font-mono text-xs text-ink">{job.type}</span>
                  <StatusPill status={job.status} />
                  <span className="ml-auto font-mono text-xs text-ink-muted">
                    {job.createdAt ? formatDateTime(job.createdAt) : '—'}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState
              title="Sin tareas registradas"
              description="Cuando se lance una ingesta, un entrenamiento o un pronóstico, aparecerá aquí."
              action={
                isAnalyst ? (
                  <Link to="/experiments">
                    <Button size="sm" variant="secondary">
                      Ir a experimentos
                    </Button>
                  </Link>
                ) : undefined
              }
            />
          )}
        </Panel>

        <Panel tone="raised">
          <PanelHeader title="Estado de la plataforma" subtitle="Señales de ejecución" />
          <dl className="divide-y divide-hairline-subtle border-t border-hairline-subtle text-[13px]">
            <div className="flex items-center justify-between gap-3 py-2">
              <dt className="text-ink-secondary">Conectividad API</dt>
              <dd>
                <StatusPill
                  status={quote.isError ? 'FAILED' : quote.isPending ? 'RUNNING' : 'SUCCEEDED'}
                  label={quote.isError ? 'error' : quote.isPending ? 'conectando' : 'ok'}
                />
              </dd>
            </div>
            <div className="flex items-center justify-between gap-3 py-2">
              <dt className="text-ink-secondary">Canal</dt>
              <dd><Badge tone="success">HTTPS</Badge></dd>
            </div>
            <div className="flex items-center justify-between gap-3 py-2">
              <dt className="text-ink-secondary">Servicio ML</dt>
              <dd><Badge tone="neutral">Solo vía backend</Badge></dd>
            </div>
            <div className="flex items-center justify-between gap-3 py-2">
              <dt className="text-ink-secondary">Sesión</dt>
              <dd><Badge tone={statusTone(user?.role === 'ADMIN' ? 'ADMIN' : 'VIEWER')}>{user?.role ?? '—'}</Badge></dd>
            </div>
          </dl>
          <div className="mt-4 border-t border-hairline-subtle pt-3">
            <p className="label-caps mb-2">Tendencia reciente · cierres diarios</p>
            {sparkValues.length >= 2 ? (
              <Sparkline values={sparkValues} stroke="var(--xmr-text-secondary)" label="Cierre de las últimas velas" />
            ) : (
              <p className="font-mono text-xs text-ink-muted">
                {recentCandles.isPending ? 'Cargando tendencia...' : 'Sin datos de velas recientes'}
              </p>
            )}
          </div>
        </Panel>
      </div>

      {/* --------------------------------------------- últimas predicciones */}
      <Panel tone="raised">
        <PanelHeader
          title="Últimas predicciones"
          subtitle="Pronósticos de cierre y dirección publicados por los modelos"
          actions={
            <Link to="/predictions">
              <Button variant="ghost" size="sm">
                Ver todas
              </Button>
            </Link>
          }
        />
        {predictions.isPending ? (
          <SkeletonTable rows={3} columns={4} />
        ) : predictions.isError ? (
          <ErrorState
            message={toApiError(predictions.error).friendlyMessage}
            requestId={toApiError(predictions.error).requestId}
            onRetry={() => void predictions.refetch()}
          />
        ) : predictions.data && predictions.data.items.length > 0 ? (
          <ul className="divide-y divide-hairline-subtle border-t border-hairline-subtle">
            {predictions.data.items.map((prediction) => (
              <li key={prediction.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2.5">
                <span className="font-mono text-xs tabular-nums text-ink-muted">{prediction.targetDate}</span>
                <span className="text-sm text-ink">{prediction.modelName ?? prediction.modelId}</span>
                <span className="font-mono text-sm tabular-nums text-ink">
                  {formatUsd(prediction.predictedClose)}
                </span>
                <span className="ml-auto flex items-center gap-2">
                  {prediction.predictedDirection ? (
                    <Badge tone={prediction.predictedDirection === 'UP' ? 'success' : 'danger'}>
                      {prediction.predictedDirection === 'UP' ? 'sube' : 'baja'}
                    </Badge>
                  ) : null}
                  <StatusPill status={prediction.status} />
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="Todavía no hay predicciones"
            description="Genera un pronóstico desde la sección de predicciones para verlo aquí."
          />
        )}
      </Panel>

      {isAnalyst ? (
        <section aria-label="Comparativa de modelos" className="overflow-hidden rounded border border-hairline-default bg-surface-2">
          <span aria-hidden="true" className="block h-0.5 bg-brand" />
          <div className="flex flex-wrap items-center justify-between gap-4 p-5">
            <div>
              <h2 className="text-base font-semibold text-ink">Comparativa de modelos</h2>
              <p className="mt-1 text-[13px] text-ink-secondary">
                MAE, RMSE, MAPE y proporción de aciertos de dirección por modelo, sobre la misma partición.
              </p>
            </div>
            <Link to="/models">
              <Button variant="secondary">Abrir comparativa</Button>
            </Link>
          </div>
        </section>
      ) : null}

      {!isAnalyst ? (
        <AnalystAccessRequestForm />
      ) : null}
    </div>
  )
}
