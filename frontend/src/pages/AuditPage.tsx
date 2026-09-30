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
      render: (row) => <span className="font-mono text-xs">{formatDateTime(row.timestamp)}</span>,
    },
    {
      key: 'actorEmail',
      header: 'Actor',
      render: (row) => <span className="font-mono text-xs">{row.actorEmail ?? row.actorId ?? 'sistema'}</span>,
    },
    { key: 'action', header: 'Accion', render: (row) => <span className="text-ink">{row.action}</span> },
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
      render: (row) => <span className="font-mono text-[11px] text-ink-muted">{row.requestId?.slice(0, 8) ?? '—'}</span>,
    },
  ]

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Administracion</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Auditoria</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Traza de acciones sensibles. Cada entrada lleva el requestId que permite correlacionar con los logs
          del backend y del servicio ML.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <StatusDot tone="danger" label="Sesion ADMIN" />
        <StatusDot tone={audit.isError ? 'danger' : audit.isPending ? 'warning' : 'success'} label={audit.isError ? 'Error al leer' : audit.isPending ? 'Consultando' : 'Registro disponible'} />
      </div>

      <Panel tone="raised">
        <PanelHeader title="Registro de auditoria" subtitle="Solo lectura. Ningun endpoint permite modificarlo desde la UI." />
        {audit.isPending ? (
          <SkeletonTable rows={6} columns={5} />
        ) : audit.isError ? (
          <ErrorState
            message={toApiError(audit.error).friendlyMessage}
            requestId={toApiError(audit.error).requestId}
            onRetry={() => void audit.refetch()}
          />
        ) : audit.data && audit.data.items.length > 0 ? (
          <>
            <DataTable caption="Registro de auditoria" columns={columns} rows={audit.data.items} rowKey={(row) => row.id} dense />
            <div className="mt-4">
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
          <EmptyState
            title="Sin entradas de auditoria"
            description="No se han registrado acciones sensibles en el periodo consultado."
          />
        )}
      </Panel>
    </div>
  )
}
