import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchAudit } from '../api'
import { toApiError } from '../api/errors'
import { Badge, EmptyState, ErrorState, Panel, PanelHeader, SkeletonTable, StatusDot } from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { formatDateTime } from '../utils/format'
import type { AuditEntry } from '../types'

export default function AuditPage() {
  const [page, setPage] = useState(0)

  const audit = useQuery({
    queryKey: ['audit', page],
    queryFn: () => fetchAudit(page, 20),
    retry: 1,
  })

  const columns: Array<Column<AuditEntry>> = [
    {
      key: 'timestamp',
      header: 'Marca de tiempo',
      render: (row) => <span className="font-mono text-xs tabular-nums">{formatDateTime(row.timestamp)}</span>,
    },
    {
      key: 'actorEmail',
      header: 'Actor',
      render: (row) => <span className="font-mono text-xs">{row.actorEmail ?? row.actorId ?? 'sistema'}</span>,
    },
    { key: 'action', header: 'Acción', render: (row) => <span className="font-medium text-ink">{row.action}</span> },
    {
      key: 'resourceType',
      header: 'Recurso',
      render: (row) => (
        <span className="font-mono text-xs text-ink-muted">
          {row.resourceType ?? '—'}
          {row.resourceId ? ` #${row.resourceId.slice(0, 8)}` : ''}
        </span>
      ),
    },
    {
      key: 'outcome',
      header: 'Resultado',
      render: (row) => <Badge tone={row.outcome === 'SUCCESS' ? 'success' : 'danger'}>{row.outcome}</Badge>,
    },
    {
      key: 'requestId',
      header: 'requestId',
      render: (row) => <span className="font-mono text-[11px] tabular-nums text-ink-muted">{row.requestId?.slice(0, 8) ?? '—'}</span>,
    },
  ]

  return (
    <div className="space-y-5">
      <div className="border-b border-hairline-subtle pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="label-caps-ticked">Administración · traza append-only</p>
            <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Auditoría</h1>
            <p className="mt-1 max-w-3xl text-[13px] text-ink-secondary">
              Traza de acciones sensibles. Cada entrada lleva el requestId que permite correlacionar con los logs
              del backend y del servicio ML.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <StatusDot tone="danger" label="Sesión ADMIN" />
            <StatusDot
              tone={audit.isError ? 'danger' : audit.isPending ? 'warning' : 'success'}
              label={audit.isError ? 'Error al leer' : audit.isPending ? 'Consultando' : 'Registro disponible'}
            />
          </div>
        </div>
      </div>

      <Panel tone="raised" flush>
        <div className="p-5 pb-3">
          <PanelHeader title="Registro de auditoría" subtitle="Solo lectura. Ningún endpoint permite modificarlo desde la UI." />
        </div>
        {audit.isPending ? (
          <div className="p-5 pt-0"><SkeletonTable rows={6} columns={5} /></div>
        ) : audit.isError ? (
          <div className="p-5 pt-0">
            <ErrorState
              message={toApiError(audit.error).friendlyMessage}
              requestId={toApiError(audit.error).requestId}
              onRetry={() => void audit.refetch()}
            />
          </div>
        ) : audit.data && audit.data.items.length > 0 ? (
          <>
            <DataTable caption="Registro de auditoria" columns={columns} rows={audit.data.items} rowKey={(row) => row.id} dense />
            <div className="px-1 pb-1">
              <Pagination
                page={audit.data.page}
                totalPages={audit.data.totalPages}
                total={audit.data.total}
                size={audit.data.size}
                onPageChange={setPage}
                disabled={audit.isFetching}
              />
            </div>
          </>
        ) : (
          <div className="p-5 pt-0">
            <EmptyState
              title="Sin entradas de auditoría"
              description="No se han registrado acciones sensibles en el periodo consultado."
            />
          </div>
        )}
      </Panel>
    </div>
  )
}
