import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  fetchPublicComparison,
  fetchPublicMetrics,
  fetchPublicModels,
  fetchPublicSeries,
  fetchPublicStatus,
  fetchPublicSummary,
} from '../api/public'
import type { ComparisonRow } from '../api'
import { DEFAULT_SYMBOL } from '../types'
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
import { CandlestickChart } from '../components/charts/CandlestickChart'
import { MetricComparisonChart } from '../components/charts/MetricComparisonChart'
import { formatDateTime, formatUsd } from '../utils/format'

/**
 * Public dashboard — the FIRST screen of the product (`/`).
 *
 * Anonymous by design (R-26): it reads only `/api/v1/public/**`, shows global
 * aggregates and never renders an identifier belonging to a user, an experiment
 * run, a job or a configuration. Advanced functions are not hidden behind a
 * dead end: they are explained and lead to `/login`, so the visitor always
 * knows what exists behind the session and how to reach it.
 *
 * Every section survives a failed or missing backend: h1, legal notice and
 * navigation render even when the API is down, because the first screen must
 * not become a blank page (T-032, R-21).
 */
export default function PublicDashboardPage() {
  const summary = useQuery({
    queryKey: ['public', 'summary', DEFAULT_SYMBOL],
    queryFn: () => fetchPublicSummary(DEFAULT_SYMBOL),
    retry: 1,
    refetchInterval: 60_000,
  })

  const series = useQuery({
    queryKey: ['public', 'series', DEFAULT_SYMBOL, 90],
    queryFn: () => fetchPublicSeries(DEFAULT_SYMBOL, 90),
    retry: 1,
    staleTime: 300_000,
  })

  const metrics = useQuery({
    queryKey: ['public', 'metrics'],
    queryFn: fetchPublicMetrics,
    retry: 1,
    staleTime: 300_000,
  })

  const comparison = useQuery({
    queryKey: ['public', 'comparison'],
    queryFn: fetchPublicComparison,
    retry: 1,
    staleTime: 300_000,
  })

  const models = useQuery({
    queryKey: ['public', 'models'],
    queryFn: fetchPublicModels,
    retry: 1,
    staleTime: 300_000,
  })

  const status = useQuery({
    queryKey: ['public', 'status'],
    queryFn: fetchPublicStatus,
    retry: 1,
    staleTime: 60_000,
  })

  const up = summary.data?.changePercent !== undefined && summary.data.changePercent >= 0
  const directionUp = metrics.data?.validation?.directionAccuracy

  return (
    <div className="space-y-8" data-testid="public-dashboard">
      {/* --------------------------------------------- cabecera de producto */}
      <div className="border-b border-hairline-subtle pb-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0 max-w-3xl">
            <p className="label-caps-ticked">Panel público · sin sesión · XMR-USD</p>
            <h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">
              Monero (XMR): capacidad predictiva evaluada
            </h1>
            <p className="mt-2 max-w-2xl text-sm leading-normal text-ink-secondary">
              Resumen de mercado, métricas y comparación de modelos en acceso anónimo. La creación de
              pronósticos, la gestión de experimentos y el histórico completo requieren iniciar sesión.
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2 border-l border-hairline-subtle pl-4">
            <StatusDot
              tone={summary.isError ? 'danger' : summary.isPending ? 'warning' : 'success'}
              label={summary.isError ? 'API sin respuesta' : summary.isPending ? 'Consultando' : 'API operativa'}
              pulse={summary.isFetching}
            />
            {status.data?.dataUpdatedAt ? (
              <Badge tone="neutral" title="Última actualización de los datos">
                Datos: {formatDateTime(status.data.dataUpdatedAt)}
              </Badge>
            ) : null}
          </div>
        </div>
      </div>

      <Disclaimer variant="short" />

      {/* --------------------------------------------- cinta de cotización */}
      <section aria-label="Resumen de mercado" data-testid="summary" className="grid gap-px overflow-hidden rounded border border-hairline-subtle bg-hairline-subtle sm:grid-cols-2 xl:grid-cols-4">
        {summary.isPending ? (
          <div className="bg-deep p-4 sm:col-span-2 xl:col-span-4">
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <SkeletonStat />
              <SkeletonStat />
              <SkeletonStat />
              <SkeletonStat />
            </div>
          </div>
        ) : summary.isError ? (
          <div className="bg-deep p-4 sm:col-span-2 xl:col-span-4">
            <ErrorState
              title="No se pudo leer el resumen de mercado"
              message={toApiError(summary.error).friendlyMessage}
              requestId={toApiError(summary.error).requestId}
              onRetry={() => void summary.refetch()}
            />
          </div>
        ) : (
          <>
            <div className="bg-deep p-4">
              <p className="label-caps">Precio XMR-USD</p>
              <p className="mt-1.5 font-mono text-[22px] font-semibold tabular-nums leading-none text-ink">
                {formatUsd(summary.data?.price)}
              </p>
              <p className="mt-1.5 font-mono text-[11px] tabular-nums text-ink-muted">
                {summary.data?.source ? `Fuente: ${summary.data.source}` : 'Último cierre disponible'}
              </p>
            </div>
            <div className="bg-deep p-4">
              <p className="label-caps">Variación</p>
              <p className={`mt-1.5 font-mono text-[22px] font-semibold tabular-nums leading-none ${up ? 'text-accent-green' : 'text-accent-red'}`}>
                {typeof summary.data?.changePercent === 'number' ? summary.data.changePercent.toFixed(2) : '—'}
                <span className="ml-1 text-xs font-normal">%</span>
              </p>
              <p className="mt-1.5 text-xs text-ink-muted">
                {summary.data?.updatedAt ? `Actualizado ${formatDateTime(summary.data.updatedAt)}` : 'Sin marca de actualización'}
                <span className={`ml-2 font-mono text-[11px] ${up ? 'text-accent-green' : 'text-accent-red'}`}>{up ? '▲ sube' : '▼ baja'}</span>
              </p>
            </div>
            <div className="bg-deep p-4">
              <p className="label-caps">Máximo / mínimo</p>
              <p className="mt-1.5 font-mono text-[22px] font-semibold tabular-nums leading-none text-ink">
                {formatUsd(summary.data?.high)} <span className="text-xs font-normal text-ink-muted">/</span> {formatUsd(summary.data?.low)}
              </p>
              <p className="mt-1.5 text-xs text-ink-muted">Sesión del último dato ingerido</p>
            </div>
            <div className="bg-deep p-4">
              <p className="label-caps">Cierre anterior</p>
              <p className="mt-1.5 font-mono text-[22px] font-semibold tabular-nums leading-none text-ink">
                {formatUsd(summary.data?.previousClose)}
              </p>
              <p className="mt-1.5 text-xs text-ink-muted">
                {summary.data?.marketTime ? `Mercado a ${formatDateTime(summary.data.marketTime)}` : 'Base de la variación diaria'}
              </p>
            </div>
          </>
        )}
      </section>

      {/* --------------------------------------------- serie histórica */}
      <Panel tone="raised">
        <PanelHeader
          title="Serie histórica"
          subtitle="Hasta 365 velas en acceso anónimo; el conjunto completo está dentro de la sesión"
        />
        {series.isPending ? (
          <SkeletonTable rows={4} columns={4} />
        ) : series.isError ? (
          <ErrorState
            title="No se pudo leer la serie histórica"
            message={toApiError(series.error).friendlyMessage}
            requestId={toApiError(series.error).requestId}
            onRetry={() => void series.refetch()}
          />
        ) : series.data && series.data.length > 0 ? (
          <CandlestickChart candles={series.data} />
        ) : (
          <EmptyState
            title="Todavía no hay velas publicadas"
            description="En cuanto se ingieran datos, la gráfica aparecerá aquí sin necesidad de recargar."
          />
        )}
      </Panel>

      {/* --------------------------------------------- métricas públicas */}
      <section aria-labelledby="public-metrics-title" data-testid="public-metrics">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
          <div>
            <p className="label-caps-ticked">Métricas públicas · resultado de validación</p>
            <h2 id="public-metrics-title" className="mt-1.5 text-xl font-semibold tracking-tight text-ink">
              {metrics.data?.experimentCode
                ? `Corrida ${metrics.data.experimentCode}`
                : 'Resultados de la última corrida'}
            </h2>
          </div>
          <Badge tone="neutral">Partición cronológica · sin mezcla</Badge>
        </div>

        {metrics.isPending ? (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
            <SkeletonStat />
          </div>
        ) : metrics.isError ? (
          <ErrorState
            title="No se pudieron leer las métricas públicas"
            message={toApiError(metrics.error).friendlyMessage}
            requestId={toApiError(metrics.error).requestId}
            onRetry={() => void metrics.refetch()}
          />
        ) : metrics.data && metrics.data.available ? (
          <Panel>
            <div className="grid gap-x-6 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
              <MetricCard
                label="MAE"
                value={formatUsd(metrics.data.validation?.mae)}
                tone="idle"
                hint="Validación · error absoluto medio"
              />
              <MetricCard
                label="RMSE"
                value={formatUsd(metrics.data.validation?.rmse)}
                tone="idle"
                hint="Validación · penaliza errores grandes"
              />
              <MetricCard
                label="MAPE"
                value={typeof metrics.data.validation?.mape === 'number' ? metrics.data.validation.mape.toFixed(2) : '—'}
                unit="%"
                tone="idle"
                hint="Validación · error porcentual medio"
              />
              <MetricCard
                label="Dirección"
                value={typeof directionUp === 'number' ? (directionUp * 100).toFixed(1) : '—'}
                unit="%"
                tone="idle"
                trend="up"
                hint="Aciertos sube / baja sobre validación"
              />
            </div>
          </Panel>
        ) : (
          <EmptyState
            title="Todavía no hay corridas publicadas"
            description="Cuando termine un experimento, sus métricas aparecerán aquí. No se muestran cifras sin una corrida real que las respalde."
          />
        )}
      </section>

      {/* --------------------------------------------- comparación */}
      <Panel tone="raised" data-testid="comparison">
        <PanelHeader
          title="Comparación de modelos"
          subtitle="Misma corrida y misma partición (validación) para todas las familias"
        />
        {comparison.isPending ? (
          <SkeletonTable rows={3} columns={4} />
        ) : comparison.isError ? (
          <ErrorState
            title="No se pudo leer la comparación"
            message={toApiError(comparison.error).friendlyMessage}
            requestId={toApiError(comparison.error).requestId}
            onRetry={() => void comparison.refetch()}
          />
        ) : comparison.data && comparison.data.length > 0 ? (
          <>
            <MetricComparisonChart rows={comparison.data.map(toComparisonRow)} />
            {/* Contraparte textual del gráfico: sin ella la comparación no es
                legible con lector de pantalla ni con CSS desactivado (WCAG). */}
            <table className="mt-4 w-full border-collapse text-left text-sm">
              <caption className="label-caps pb-2 text-left">
                Comparación de modelos sobre validación
              </caption>
              <thead>
                <tr className="border-b border-hairline bg-surface-inset">
                  <th scope="col" className="px-3 py-2 font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">Modelo</th>
                  <th scope="col" className="px-3 py-2 font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">Familia</th>
                  <th scope="col" className="px-3 py-2 text-right font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">MAE</th>
                  <th scope="col" className="px-3 py-2 text-right font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">RMSE</th>
                  <th scope="col" className="px-3 py-2 text-right font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">MAPE</th>
                  <th scope="col" className="px-3 py-2 text-right font-mono text-[11px] font-medium uppercase tracking-wide text-ink-muted">Dirección</th>
                </tr>
              </thead>
              <tbody>
                {comparison.data.map((row) => (
                  <tr
                    key={row.label}
                    className="border-b border-hairline-subtle/60 last:border-b-0 hover:bg-surface-2"
                    style={row.isChampion ? { boxShadow: 'inset 2px 0 0 var(--xmr-brand)' } : undefined}
                  >
                    <th scope="row" className="px-3 py-2 text-left font-medium text-ink">
                      {row.label}
                      {row.isChampion ? (
                        <Badge tone="brand" title="Versión campeona elegida por validación" className="ml-2">
                          campeón
                        </Badge>
                      ) : null}
                    </th>
                    <td className="px-3 py-2 font-mono text-xs text-ink-muted">{row.family}</td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums text-ink">
                      {formatUsd(row.metrics.mae)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums text-ink">
                      {formatUsd(row.metrics.rmse)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums text-ink">
                      {typeof row.metrics.mape === 'number' ? `${row.metrics.mape.toFixed(2)} %` : '—'}
                    </td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums text-ink">
                      {typeof row.metrics.directionAccuracy === 'number'
                        ? `${(row.metrics.directionAccuracy * 100).toFixed(1)} %`
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <EmptyState
            title="Sin comparación publicada"
            description="La comparación se publica cuando hay al menos una corrida completada con métricas de validación."
          />
        )}
      </Panel>

      {/* --------------------------------------------- catálogo + estado */}
      <div className="grid gap-5 lg:grid-cols-5">
        <section aria-labelledby="public-models-title" className="space-y-3 lg:col-span-3">
          <div>
            <p className="label-caps-ticked">Estado general de modelos</p>
            <h2 id="public-models-title" className="mt-1.5 text-xl font-semibold tracking-tight text-ink">
              Catálogo y versiones campeonas
            </h2>
          </div>

          <Panel data-testid="models-list" flush>
            {models.isPending ? (
              <div className="p-5"><SkeletonTable rows={4} columns={3} /></div>
            ) : models.isError ? (
              <div className="p-5">
                <ErrorState
                  title="No se pudo leer el catálogo de modelos"
                  message={toApiError(models.error).friendlyMessage}
                  requestId={toApiError(models.error).requestId}
                  onRetry={() => void models.refetch()}
                />
              </div>
            ) : models.data && models.data.length > 0 ? (
              <ul className="divide-y divide-hairline-subtle">
                {models.data.map((model) => (
                  <li key={model.name} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 hover:bg-surface-2">
                    <span className="text-sm font-medium text-ink">{model.name}</span>
                    <Badge tone="neutral">{model.family}</Badge>
                    <span className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">{model.task}</span>
                    <span className="ml-auto">
                      {model.hasChampion ? (
                        <Badge tone="brand" title="Versión campeona elegida por validación">
                          campeón
                        </Badge>
                      ) : (
                        <Badge tone="neutral">sin campeón</Badge>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <div className="p-5">
                <EmptyState
                  title="El catálogo de modelos está vacío"
                  description="Los modelos aparecen aquí cuando se registran en el backend."
                />
              </div>
            )}
          </Panel>
        </section>

        <div className="lg:col-span-2">
          <Panel data-testid="public-status" className="h-full" tone="raised">
            <PanelHeader
              title="Estado de actualización"
              subtitle="Frescura de los datos y última corrida terminada"
            />
            {status.isPending ? (
              <SkeletonTable rows={2} columns={2} />
            ) : status.isError ? (
              <ErrorState
                title="No se pudo leer el estado del sistema"
                message={toApiError(status.error).friendlyMessage}
                requestId={toApiError(status.error).requestId}
                onRetry={() => void status.refetch()}
              />
            ) : status.data ? (
              <dl className="divide-y divide-hairline-subtle border-t border-hairline-subtle">
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Última actualización de datos</dt>
                  <dd className="font-mono text-xs text-ink" data-testid="data-updated-at">
                    {status.data.dataUpdatedAt ? formatDateTime(status.data.dataUpdatedAt) : 'sin datos'}
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Velas almacenadas</dt>
                  <dd className="font-mono text-xs tabular-nums text-ink">{status.data.dataPoints}</dd>
                </div>
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Modelos / con campeón</dt>
                  <dd className="font-mono text-xs tabular-nums text-ink">
                    {status.data.models} / {status.data.champions}
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Última corrida</dt>
                  <dd className="font-mono text-xs text-ink">
                    {status.data.experimentCode
                      ? `${status.data.experimentCode} · ${status.data.experimentStatus}`
                      : 'sin corridas'}
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Documentos legales</dt>
                  <dd className="font-mono text-xs text-ink">v{status.data.legalVersion}</dd>
                </div>
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Generado</dt>
                  <dd className="font-mono text-xs text-ink">{formatDateTime(status.data.generatedAt)}</dd>
                </div>
              </dl>
            ) : null}
          </Panel>
        </div>
      </div>

      {/* --------------------------------------------- funciones avanzadas */}
      <section data-testid="advanced-locked" aria-labelledby="advanced-locked-title" className="overflow-hidden rounded border border-hairline-default bg-surface-2">
        <span aria-hidden="true" className="block h-0.5 bg-brand" />
        <div className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 id="advanced-locked-title" className="text-base font-semibold text-ink">Funciones avanzadas: requieren sesión</h2>
            <p className="mt-1 max-w-2xl text-[13px] leading-normal text-ink-secondary">
              La vista anónima no incluye experimentos, trabajos de ML, predicciones individuales,
              exportaciones, configuración interna, administración ni auditoría. Inicia sesión para
              abrir el panel completo.
            </p>
            <ul className="mt-3 flex flex-wrap gap-1.5">
              {['Experimentos', 'Trabajos ML', 'Predicciones', 'Exportaciones', 'Administración'].map(
                (label) => (
                  <li key={label}>
                    <Badge tone="neutral" title="Requiere iniciar sesión">
                      {label}
                    </Badge>
                  </li>
                ),
              )}
            </ul>
          </div>
          <div className="flex shrink-0 flex-wrap gap-2">
            <Link to="/login">
              <Button size="lg" variant="primary">
                Iniciar sesión
              </Button>
            </Link>
            <Link to="/register">
              <Button size="lg" variant="secondary">
                Registrarse
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* --------------------------------------------- limitaciones */}
      <section aria-labelledby="public-legal-title">
        <p className="label-caps-ticked">Limitaciones</p>
        <h2 id="public-legal-title" className="mb-3 mt-1.5 text-xl font-semibold tracking-tight text-ink">
          Qué muestra y qué no muestra esta vista
        </h2>
        <Panel>
          <ul className="list-disc space-y-2 pl-5 text-sm leading-normal text-ink-secondary marker:text-ink-muted">
            <li>
              Muestra agregados globales de mercado, métricas de evaluación publicadas y el estado
              general de los modelos: todo lo que cualquiera puede verificar sin una cuenta.
            </li>
            <li>
              No muestra predicciones de usuarios, configuraciones internas, registros de auditoría,
              identificadores de experimentos ni datos personales.
            </li>
            <li>
              Las métricas proceden de particiones cronológicas y pueden no repetirse en el futuro:
              no es asesoría financiera ni una promesa de rentabilidad.
            </li>
            <li>
              Los datos se actualizan de forma periódica; la fecha exacta de la última ingesta está
              en el panel de estado de esta misma página.
            </li>
          </ul>
          <div className="mt-4">
            <Disclaimer variant="full" />
          </div>
        </Panel>
      </section>
    </div>
  )
}

/** Public rows carry no internal identifiers; the chart only needs these. */
function toComparisonRow(row: { label: string; family?: string; isChampion?: boolean; metrics: unknown }): ComparisonRow {
  return {
    label: row.label,
    modelName: row.label,
    family: row.family,
    isChampion: row.isChampion,
    metrics: row.metrics as ComparisonRow['metrics'],
  }
}
