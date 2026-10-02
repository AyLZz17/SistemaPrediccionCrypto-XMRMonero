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
  Section,
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
    <div className="space-y-9" data-testid="public-dashboard">
      {/* ------------------------------------------------------ masthead */}
      <div className="flex flex-wrap items-start justify-between gap-4 border-b-2 border-hairline-strong pb-5">
        <div className="min-w-0 max-w-3xl">
          <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">
            Panel público · sin sesión · XMR-USD
          </p>
          <h1 className="mt-2 font-mono text-2xl font-semibold tracking-tight text-ink sm:text-3xl">
            Monero (XMR): capacidad predictiva evaluada
          </h1>
          <p className="mt-2 max-w-2xl text-sm leading-normal text-ink-secondary">
            Resumen de mercado, métricas y comparación de modelos en acceso anónimo. La creación de
            pronósticos, la gestión de experimentos y el histórico completo requieren iniciar sesión.
          </p>
        </div>
        <dl className="grid shrink-0 grid-cols-2 gap-px border border-hairline-subtle bg-hairline-subtle">
          <div className="bg-deep px-4 py-2.5">
            <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">API</dt>
            <dd className="mt-1">
              <StatusDot
                tone={summary.isError ? 'danger' : summary.isPending ? 'warning' : 'success'}
                label={summary.isError ? 'API sin respuesta' : summary.isPending ? 'Consultando' : 'API operativa'}
                pulse={summary.isFetching}
              />
            </dd>
          </div>
          <div className="bg-deep px-4 py-2.5">
            <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">Datos</dt>
            <dd className="mt-1">
              {status.data?.dataUpdatedAt ? (
                <Badge tone="neutral" title="Última actualización de los datos">
                  Datos: {formatDateTime(status.data.dataUpdatedAt)}
                </Badge>
              ) : (
                <span className="font-mono text-[11px] text-ink-muted">—</span>
              )}
            </dd>
          </div>
        </dl>
      </div>

      <Disclaimer variant="short" />

      {/* ------------------------------------------------------- 01 mercado */}
      <Section
        index="01"
        eyebrow="Mercado ahora"
        title="Resumen de mercado"
        actions={
          <span className="font-mono text-[11px] tabular-nums text-ink-muted">
            {summary.data?.source ? `Fuente: ${summary.data.source}` : ''}
          </span>
        }
      >
        <div aria-label="Resumen de mercado" data-testid="summary" className="grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
          {summary.isPending ? (
            <div className="sm:col-span-2 xl:col-span-4">
              <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
                <SkeletonStat />
                <SkeletonStat />
                <SkeletonStat />
                <SkeletonStat />
              </div>
            </div>
          ) : summary.isError ? (
            <div className="sm:col-span-2 xl:col-span-4">
              <ErrorState
                title="No se pudo leer el resumen de mercado"
                message={toApiError(summary.error).friendlyMessage}
                requestId={toApiError(summary.error).requestId}
                onRetry={() => void summary.refetch()}
              />
            </div>
          ) : (
            <>
              <MetricCard
                label="Precio XMR-USD"
                value={formatUsd(summary.data?.price)}
                tone="idle"
                trend={up ? 'up' : 'down'}
                hint="Último cierre disponible"
              />
              <MetricCard
                label="Variación"
                value={typeof summary.data?.changePercent === 'number' ? summary.data.changePercent.toFixed(2) : '—'}
                unit="%"
                tone={up ? 'success' : 'danger'}
                hint={
                  summary.data?.updatedAt
                    ? `Actualizado ${formatDateTime(summary.data.updatedAt)}`
                    : 'Sin marca de actualización'
                }
              />
              <MetricCard
                label="Máximo / mínimo"
                value={`${formatUsd(summary.data?.high)} / ${formatUsd(summary.data?.low)}`}
                tone="idle"
                hint="Sesión del último dato ingerido"
              />
              <MetricCard
                label="Cierre anterior"
                value={formatUsd(summary.data?.previousClose)}
                tone="idle"
                hint={
                  summary.data?.marketTime
                    ? `Mercado a ${formatDateTime(summary.data.marketTime)}`
                    : 'Base de la variación diaria'
                }
              />
            </>
          )}
        </div>
      </Section>

      {/* ------------------------------------------------------- 02 serie */}
      <Section
        index="02"
        eyebrow="Serie histórica"
        title="Velas diarias de XMR-USD"
        description="Hasta 365 velas en acceso anónimo; el conjunto completo está dentro de la sesión"
      >
        <div className="console-well p-3 sm:p-4">
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
        </div>
      </Section>

      {/* ------------------------------------------------------- 03 métricas */}
      <section aria-labelledby="public-metrics-title" data-testid="public-metrics">
        <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2 border-b-2 border-hairline-strong pb-2.5">
          <div>
            <p className="flex items-baseline gap-2 font-mono text-[11px] uppercase tracking-wide">
              <span aria-hidden="true" className="font-semibold tabular-nums text-brand-strong">03</span>
              <span className="text-ink-muted">Métricas públicas · resultado de validación</span>
            </p>
            <h2 id="public-metrics-title" className="mt-1 text-xl font-semibold tracking-tight text-ink">
              {metrics.data?.experimentCode
                ? `Corrida ${metrics.data.experimentCode}`
                : 'Resultados de la última corrida'}
            </h2>
          </div>
          <Badge tone="neutral">Partición cronológica · sin mezcla</Badge>
        </div>

        <div className="pt-4">
          {metrics.isPending ? (
            <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
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
            <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
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
          ) : (
            <EmptyState
              title="Todavía no hay corridas publicadas"
              description="Cuando termine un experimento, sus métricas aparecerán aquí. No se muestran cifras sin una corrida real que las respalde."
            />
          )}
        </div>
      </section>

      {/* ------------------------------------------------------- 04 comparativa */}
      <Section
        index="04"
        eyebrow="Evidencia comparada"
        title="Comparación de modelos"
        description="Misma corrida y misma partición (validación) para todas las familias"
        data-testid="comparison"
      >
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
            <div className="console-well p-3 sm:p-4">
              <MetricComparisonChart rows={comparison.data.map(toComparisonRow)} />
            </div>
            {/* Contraparte textual del gráfico: sin ella la comparación no es
                legible con lector de pantalla ni con CSS desactivado (WCAG). */}
            <div className="relative mt-4 w-full overflow-x-auto">
              <table className="w-full border-collapse text-left text-sm">
                <caption className="label-caps pb-2 text-left">
                  Comparación de modelos sobre validación
                </caption>
                <thead>
                  <tr className="border-b-2 border-hairline-strong">
                    <th scope="col" className="py-2 pr-3 font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Modelo</th>
                    <th scope="col" className="py-2 pr-3 font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Familia</th>
                    <th scope="col" className="py-2 pr-3 text-right font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">MAE</th>
                    <th scope="col" className="py-2 pr-3 text-right font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">RMSE</th>
                    <th scope="col" className="py-2 pr-3 text-right font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">MAPE</th>
                    <th scope="col" className="py-2 text-right font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Dirección</th>
                  </tr>
                </thead>
                <tbody>
                  {comparison.data.map((row) => (
                    <tr key={row.label} className="border-b border-hairline-subtle last:border-b-0 hover:bg-surface-2">
                      <th scope="row" className="py-2 pr-3 text-left font-semibold text-ink">
                        <span aria-hidden="true" className={`mr-2 inline-block h-2 w-2 ${row.isChampion ? 'bg-brand' : 'bg-ink-muted'}`} />
                        {row.label}
                        {row.isChampion ? (
                          <Badge tone="brand" title="Versión campeona elegida por validación" className="ml-2">
                            campeón
                          </Badge>
                        ) : null}
                      </th>
                      <td className="py-2 pr-3 font-mono text-xs text-ink-muted">{row.family}</td>
                      <td className="py-2 pr-3 text-right font-mono tabular-nums text-ink">
                        {formatUsd(row.metrics.mae)}
                      </td>
                      <td className="py-2 pr-3 text-right font-mono tabular-nums text-ink">
                        {formatUsd(row.metrics.rmse)}
                      </td>
                      <td className="py-2 pr-3 text-right font-mono tabular-nums text-ink">
                        {typeof row.metrics.mape === 'number' ? `${row.metrics.mape.toFixed(2)} %` : '—'}
                      </td>
                      <td className="py-2 text-right font-mono tabular-nums text-ink">
                        {typeof row.metrics.directionAccuracy === 'number'
                          ? `${(row.metrics.directionAccuracy * 100).toFixed(1)} %`
                          : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <EmptyState
            title="Sin comparación publicada"
            description="La comparación se publica cuando hay al menos una corrida completada con métricas de validación."
          />
        )}
      </Section>

      {/* --------------------------------------------- 05 catálogo + estado */}
      <div className="grid items-start gap-x-10 gap-y-9 lg:grid-cols-5">
        <section aria-labelledby="public-models-title" className="min-w-0 lg:col-span-3">
          <div className="border-b-2 border-hairline-strong pb-2.5">
            <p className="flex items-baseline gap-2 font-mono text-[11px] uppercase tracking-wide">
              <span aria-hidden="true" className="font-semibold tabular-nums text-brand-strong">05</span>
              <span className="text-ink-muted">Estado general de modelos</span>
            </p>
            <h2 id="public-models-title" className="mt-1 text-xl font-semibold tracking-tight text-ink">
              Catálogo y versiones campeonas
            </h2>
          </div>

          <div className="pt-4" data-testid="models-list">
            {models.isPending ? (
              <SkeletonTable rows={4} columns={3} />
            ) : models.isError ? (
              <ErrorState
                title="No se pudo leer el catálogo de modelos"
                message={toApiError(models.error).friendlyMessage}
                requestId={toApiError(models.error).requestId}
                onRetry={() => void models.refetch()}
              />
            ) : models.data && models.data.length > 0 ? (
              <ul className="divide-y divide-hairline-subtle border-t-2 border-hairline-strong">
                {models.data.map((model) => (
                  <li key={model.name} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2.5">
                    <span aria-hidden="true" className={`inline-block h-2 w-2 ${model.hasChampion ? 'bg-brand' : 'bg-ink-muted'}`} />
                    <span className="text-sm font-semibold text-ink">{model.name}</span>
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
              <EmptyState
                title="El catálogo de modelos está vacío"
                description="Los modelos aparecen aquí cuando se registran en el backend."
              />
            )}
          </div>
        </section>

        <section aria-labelledby="public-status-title" className="min-w-0 lg:col-span-2" data-testid="public-status">
          <div className="border-b-2 border-hairline-strong pb-2.5">
            <p className="flex items-baseline gap-2 font-mono text-[11px] uppercase tracking-wide">
              <span aria-hidden="true" className="font-semibold tabular-nums text-brand-strong">06</span>
              <span className="text-ink-muted">Frescura y trazabilidad</span>
            </p>
            <h2 id="public-status-title" className="mt-1 text-xl font-semibold tracking-tight text-ink">
              Estado de actualización
            </h2>
          </div>

          <div className="pt-4">
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
              <dl className="divide-y divide-hairline-subtle border-t-2 border-hairline-strong">
                <div className="flex items-center justify-between gap-3 py-2">
                  <dt className="text-[13px] text-ink-secondary">Última actualización de datos</dt>
                  <dd className="font-mono text-xs tabular-nums text-ink" data-testid="data-updated-at">
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
                  <dd className="font-mono text-xs tabular-nums text-ink">{formatDateTime(status.data.generatedAt)}</dd>
                </div>
              </dl>
            ) : null}
          </div>
        </section>
      </div>

      {/* --------------------------------------------------- 07 acceso */}
      <section data-testid="advanced-locked" aria-labelledby="advanced-locked-title" className="border border-hairline-default bg-surface-1 p-5 sm:p-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="min-w-0">
            <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">07 · Zona restringida</p>
            <h2 id="advanced-locked-title" className="mt-1 text-lg font-semibold tracking-tight text-ink">Funciones avanzadas: requieren sesión</h2>
            <p className="mt-1.5 max-w-2xl text-[13px] leading-normal text-ink-secondary">
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

      {/* --------------------------------------------------- 08 límites */}
      <section aria-labelledby="public-legal-title">
        <div className="border-b-2 border-hairline-strong pb-2.5">
          <p className="flex items-baseline gap-2 font-mono text-[11px] uppercase tracking-wide">
            <span aria-hidden="true" className="font-semibold tabular-nums text-brand-strong">08</span>
            <span className="text-ink-muted">Limitaciones</span>
          </p>
          <h2 id="public-legal-title" className="mt-1 text-xl font-semibold tracking-tight text-ink">
            Qué muestra y qué no muestra esta vista
          </h2>
        </div>
        <div className="grid gap-6 pt-4 lg:grid-cols-2">
          <ul className="list-[square] space-y-2 pl-5 text-sm leading-normal text-ink-secondary marker:text-ink-muted">
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
          <Disclaimer variant="full" />
        </div>
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
