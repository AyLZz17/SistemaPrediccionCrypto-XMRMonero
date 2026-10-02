import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchJobs } from '../api'
import { toApiError } from '../api/errors'
import {
  ActivityBar,
  Button,
  EmptyState,
  ErrorState,
  MetricCard,
  Notice,
  Panel,
  PanelHeader,
  SelectField,
  StatusDot,
  TextField,
} from '../components/ui'
import { formatDateTime } from '../utils/format'
import type { Job } from '../types'

/** Terminal-style view of the operational log derived from the job records. */
export default function LogsPage() {
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [severity, setSeverity] = useState('ALL')
  const [search, setSearch] = useState('')

  const jobs = useQuery({
    queryKey: ['jobs', 0, 50],
    queryFn: () => fetchJobs(0, 50),
    refetchInterval: autoRefresh ? 10_000 : false,
    retry: 1,
  })

  const lines = useMemo(() => buildLogLines(jobs.data?.items ?? []), [jobs.data])

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return lines.filter((line) => {
      if (severity !== 'ALL' && line.level !== severity) return false
      if (!needle) return true
      return line.text.toLowerCase().includes(needle)
    })
  }, [lines, severity, search])

  const errors = lines.filter((line) => line.level === 'ERROR').length

  return (
    <div className="space-y-5">
      <div className="border-b border-hairline-subtle pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="label-caps-ticked">Administración · consola operativa</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Logs</h1>
            <p className="mt-1 text-[13px] text-ink-secondary">
              Registro operativo de tareas. No se exponen tokens, credenciales ni datos sensibles.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <StatusDot tone={jobs.isError ? 'danger' : 'success'} label={jobs.isError ? 'Error al leer' : 'Stream activo'} pulse={autoRefresh} />
            <Button size="sm" variant="secondary" onClick={() => void jobs.refetch()}>
              Refrescar
            </Button>
          </div>
        </div>
      </div>

      <section aria-label="Resumen del log" className="grid gap-x-6 gap-y-5 rounded border border-hairline-subtle bg-deep p-4 sm:grid-cols-3">
        <MetricCard label="Eventos" value={lines.length} tone="idle" />
        <MetricCard label="Errores" value={errors} tone={errors > 0 ? 'danger' : 'idle'} />
        <MetricCard
          label="Tareas en curso"
          value={lines.filter((line) => line.level === 'RUNNING').length}
          tone="active"
        />
      </section>

      <Panel tone="raised">
        <PanelHeader
          title="Consola"
          subtitle="Salida de la cola de tareas"
          actions={
            <label className="flex cursor-pointer items-center gap-2 font-mono text-[11px] uppercase tracking-wide text-ink-secondary">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(event) => setAutoRefresh(event.target.checked)}
                className="h-3.5 w-3.5 rounded-sm border-hairline-strong bg-surface-2 accent-brand"
              />
              Auto refresco
            </label>
          }
        />

        <div className="mb-4 grid gap-3 sm:grid-cols-2">
          <TextField
            label="Buscar"
            name="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="filtrar por id, tipo o mensaje"
            className="font-mono text-xs"
          />
          <SelectField label="Severidad" name="severity" value={severity} onChange={(event) => setSeverity(event.target.value)}>
            <option value="ALL">Todas</option>
            <option value="INFO">INFO</option>
            <option value="RUNNING">RUNNING</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="WARN">WARN</option>
            <option value="ERROR">ERROR</option>
          </SelectField>
        </div>

        <div className="mb-4">
          <ActivityBar label="Actividad de la cola" tone="active" progress={jobs.isFetching ? 0.6 : 1} />
        </div>

        {jobs.isPending ? (
          <div className="skeleton-bar h-64 w-full" role="status" aria-label="Cargando logs" />
        ) : jobs.isError ? (
          <ErrorState
            message={toApiError(jobs.error).friendlyMessage}
            requestId={toApiError(jobs.error).requestId}
            onRetry={() => void jobs.refetch()}
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            title="Sin líneas que mostrar"
            description="Ajusta el filtro o espera a que se generen nuevas tareas."
          />
        ) : (
          <div
            role="log"
            aria-label="Registro de operaciones"
            aria-live="polite"
            className="max-h-[28rem] overflow-y-auto rounded-sm border border-hairline-subtle bg-surface-inset p-3 font-mono text-xs leading-relaxed"
          >
            <ul className="space-y-1">
              {filtered.map((line, index) => (
                <li key={`${line.timestamp}-${index}`} className="flex flex-wrap gap-x-2 border-b border-hairline-subtle/50 py-0.5 last:border-b-0">
                  <span className="shrink-0 tabular-nums text-ink-muted">{formatDateTime(line.timestamp)}</span>
                  <span
                    className={
                      line.level === 'ERROR'
                        ? 'shrink-0 text-accent-red'
                        : line.level === 'WARN'
                          ? 'shrink-0 text-accent-amber'
                          : line.level === 'SUCCESS'
                            ? 'shrink-0 text-accent-green'
                            : line.level === 'RUNNING'
                              ? 'shrink-0 text-accent-cyan'
                              : 'shrink-0 text-ink-secondary'
                    }
                  >
                    [{line.level.padEnd(7, ' ')}]
                  </span>
                  <span className="min-w-0 flex-1 break-words text-ink-secondary">{line.text}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </Panel>

      <Notice>
        Los identificadores requestId visibles en los errores permiten correlacionar esta vista con los logs del
        backend y del servicio de ML sin exponer credenciales.
      </Notice>
    </div>
  )
}

type Level = 'INFO' | 'RUNNING' | 'SUCCESS' | 'WARN' | 'ERROR'

interface LogLine {
  timestamp: string
  level: Level
  text: string
}

function buildLogLines(jobs: Job[]): LogLine[] {
  const lines: LogLine[] = []
  for (const job of jobs) {
    lines.push({
      timestamp: job.createdAt ?? new Date(0).toISOString(),
      level: 'INFO',
      text: `job ${job.id} tipo=${job.type} encolada`,
    })
    if (job.startedAt) {
      lines.push({ timestamp: job.startedAt, level: 'RUNNING', text: `job ${job.id} en ejecucion` })
    }
    if (job.message) {
      lines.push({
        timestamp: job.finishedAt ?? job.startedAt ?? job.createdAt ?? new Date(0).toISOString(),
        level: job.status === 'FAILED' ? 'ERROR' : job.status === 'CANCELLED' ? 'WARN' : 'INFO',
        text: `job ${job.id} ${job.message}`,
      })
    }
    if (job.finishedAt) {
      lines.push({
        timestamp: job.finishedAt,
        level:
          job.status === 'SUCCEEDED'
            ? 'SUCCESS'
            : job.status === 'FAILED'
              ? 'ERROR'
              : job.status === 'CANCELLED'
                ? 'WARN'
                : 'INFO',
        text: `job ${job.id} finalizada con estado ${job.status}`,
      })
    }
  }
  return lines.sort((a, b) => a.timestamp.localeCompare(b.timestamp)).reverse()
}
