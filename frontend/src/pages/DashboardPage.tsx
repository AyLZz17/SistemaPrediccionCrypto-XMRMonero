import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchLatestQuote } from '../api/market'
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
import { formatUsd, formatDateTime } from '../utils/format'

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

  const runningJobs = jobs.data?.items.filter((job) => job.status === 'RUNNING' || job.status === 'QUEUED') ?? []
  const activeCount = runningJobs.length

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="label-caps">Centro de operaciones</p>
          <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">
            Hola, {user?.fullName?.split(' ')[0] ?? 'usuario'}
          </h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Estado del sistema, tareas en ejecucion y ultimo parte de predicciones.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <StatusDot
            tone={quote.isError ? 'danger' : quote.isPending ? 'warning' : 'success'}
            label={quote.isError ? 'API sin respuesta' : quote.isPending ? 'Consultando' : 'API operativa'}
            pulse={quote.isFetching}
          />
          <StatusDot tone={activeCount > 0 ? 'active' : 'idle'} label={`${activeCount} jobs activos`} pulse={activeCount > 0} />
        </div>
      </div>

      <Disclaimer variant="short" />

      <section aria-label="Indicadores principales" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {quote.isPending ? (
          <>
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
          </>
        ) : quote.isError ? (
          <div className="sm:col-span-2 xl:col-span-4">
            <ErrorState
              title="No se pudo leer el precio de mercado"
              message={toApiError(quote.error).friendlyMessage}
              requestId={toApiError(quote.error).requestId}
              onRetry={() => void quote.refetch()}
            />
          </div>
        ) : (
          <>
            <MetricCard
              label="Precio XMR-USD"
              value={formatUsd(quote.data?.price)}
              tone="active"
              trend={typeof quote.data?.changePercent === 'number' && quote.data.changePercent >= 0 ? 'up' : 'down'}
              hint={quote.data?.source ? `Fuente: ${quote.data.source}` : 'Ultimo cierre disponible'}
            />
            <MetricCard
              label="Variacion diaria"
              value={
                typeof quote.data?.changePercent === 'number' ? quote.data.changePercent.toFixed(2) : '—'
              }
              unit="%"
              tone={typeof quote.data?.changePercent === 'number' && quote.data.changePercent >= 0 ? 'success' : 'danger'}
              hint={quote.data?.updatedAt ? `Actualizado ${formatDateTime(quote.data.updatedAt)}` : undefined}
            />
            <MetricCard
              label="Predicciones listadas"
              value={predictions.data?.total ?? 0}
              tone="idle"
              hint="Historico guardado en el backend"
            />
            <MetricCard
              label="Jobs en curso"
              value={activeCount}
              tone={activeCount > 0 ? 'active' : 'idle'}
              hint="Entrenamiento, ingesta y pronostico"
            />
          </>
        )}
      </section>

      <div className="grid gap-4 lg:grid-cols-3">
        <Panel className="lg:col-span-2" tone="raised">
          <PanelHeader
            title="Actividad reciente"
            subtitle="Ultimas tareas encoladas y en ejecucion"
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
            <ul className="divide-y divide-hairline-subtle">
              {jobs.data.items.map((job) => (
                <li key={job.id} className="flex flex-wrap items-center gap-3 py-2.5">
                  <span className="font-mono text-xs text-ink-muted">{job.id.slice(0, 8)}</span>
                  <span className="text-sm text-ink">{job.type}</span>
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
              description="Cuando se lance una ingesta, un entrenamiento o un pronostico, aparecera aqui."
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
          <PanelHeader title="Estado del sistema" subtitle="Senales de ejecucion" />
          <ul className="space-y-3 text-sm">
            <li className="flex items-center justify-between gap-3">
              <span className="text-ink-secondary">Conectividad API</span>
              <StatusPill
                status={quote.isError ? 'FAILED' : quote.isPending ? 'RUNNING' : 'SUCCEEDED'}
                label={quote.isError ? 'error' : quote.isPending ? 'conectando' : 'ok'}
              />
            </li>
            <li className="flex items-center justify-between gap-3">
              <span className="text-ink-secondary">Canal</span>
              <Badge tone="success">HTTPS</Badge>
            </li>
            <li className="flex items-center justify-between gap-3">
              <span className="text-ink-secondary">Servicio ML</span>
              <Badge tone="neutral">Solo via backend</Badge>
            </li>
            <li className="flex items-center justify-between gap-3">
              <span className="text-ink-secondary">Sesion</span>
              <Badge tone={statusTone(user?.role === 'ADMIN' ? 'ADMIN' : 'VIEWER')}>{user?.role ?? '—'}</Badge>
            </li>
          </ul>
          <div className="mt-5 border-t border-hairline-subtle pt-4">
            <p className="label-caps mb-2">Tendencia reciente</p>
            <Sparkline values={[168.2, 169.9, 168.7, 171.4, 173.1, 172.2, 174.6]} />
          </div>
        </Panel>
      </div>

      <Panel tone="raised">
        <PanelHeader
          title="Ultimas predicciones"
          subtitle="Pronosticos de cierre y direccion publicados por los modelos"
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
          <ul className="divide-y divide-hairline-subtle">
            {predictions.data.items.map((prediction) => (
              <li key={prediction.id} className="flex flex-wrap items-center gap-3 py-2.5">
                <span className="font-mono text-xs text-ink-muted">{prediction.targetDate}</span>
                <span className="text-sm text-ink">{prediction.modelName ?? prediction.modelId}</span>
                <span className="font-mono text-sm tabular-nums text-ink">
                  {formatUsd(prediction.predictedClose)}
                </span>
                <StatusPill status={prediction.status} />
                {prediction.predictedDirection ? (
                  <Badge tone={prediction.predictedDirection === 'UP' ? 'success' : 'danger'}>
                    {prediction.predictedDirection === 'UP' ? 'sube' : 'baja'}
                  </Badge>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="Todavia no hay predicciones"
            description="Genera un pronostico desde la seccion de predicciones para verlo aqui."
          />
        )}
      </Panel>

      {isAnalyst ? (
        <Panel tone="accent">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="text-base font-semibold text-ink">Comparativa de modelos</h2>
              <p className="mt-1 text-sm text-ink-secondary">
                MAE, RMSE, MAPE y proporcion de aciertos de direccion por modelo, sobre la misma particion.
              </p>
            </div>
            <Link to="/models">
              <Button variant="secondary">Abrir comparativa</Button>
            </Link>
          </div>
        </Panel>
      ) : null}
    </div>
  )
}
