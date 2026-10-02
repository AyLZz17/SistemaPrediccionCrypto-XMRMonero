import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createAnalystAccessRequest, fetchMyAnalystAccessRequest } from '../../api/analyst'
import { CUESTIONARIO_ITEMS, type CreateAnalystAccessRequest } from '../../types/analyst'
import { toApiError } from '../../api/errors'
import { Alert, Badge, Button, Panel, PanelHeader, TextField } from '../ui'
import { formatDateTime } from '../../utils/format'

const EMPTY_FORM: CreateAnalystAccessRequest = {
  motivo: '',
  usoPrevisto: '',
  aceptaRiesgos: false,
  aceptaLimitaciones: false,
  aceptaMetricas: false,
  aceptaNoGarantia: false,
  aceptaNoOperaciones: false,
  aceptaNoBacktesting: false,
  aceptaRolAnalyst: false,
  aceptaNoRentabilidad: false,
}

const STATUS_LABELS: Record<string, { label: string; tone: 'warning' | 'success' | 'danger' | 'neutral' }> = {
  PENDING: { label: 'Pendiente de revisión', tone: 'warning' },
  APPROVED: { label: 'Aprobada', tone: 'success' },
  REJECTED: { label: 'Rechazada', tone: 'danger' },
  REVOKED: { label: 'Revocada', tone: 'neutral' },
}

export function AnalystAccessRequestForm() {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<CreateAnalystAccessRequest>(EMPTY_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const myRequest = useQuery({
    queryKey: ['analyst-access-request', 'my'],
    queryFn: fetchMyAnalystAccessRequest,
    retry: 1,
  })

  const createMutation = useMutation({
    mutationFn: createAnalystAccessRequest,
    onSuccess: () => {
      setDone(true)
      setFormError(null)
      setForm(EMPTY_FORM)
      void queryClient.invalidateQueries({ queryKey: ['analyst-access-request'] })
    },
    onError: (error) => setFormError(toApiError(error).friendlyMessage),
  })

  const request = myRequest.data

  if (request) {
    const statusInfo = STATUS_LABELS[request.status] ?? { label: request.status, tone: 'neutral' as const }
    return (
      <Panel tone="strong">
        <PanelHeader title="Solicitud de acceso ANALYST" subtitle="Estado de tu solicitud" />
        <dl className="divide-y divide-hairline-subtle border-t-2 border-hairline-strong">
          <div className="flex flex-wrap items-center gap-3 py-2.5">
            <dt className="sr-only">Estado</dt>
            <dd><Badge tone={statusInfo.tone}>{statusInfo.label}</Badge></dd>
            <dd className="font-mono text-xs tabular-nums text-ink-muted">
              {request.createdAt ? formatDateTime(request.createdAt) : ''}
            </dd>
          </div>
        </dl>
        <div className="mt-3 space-y-3">
          {request.status === 'PENDING' ? (
            <p className="text-sm leading-normal text-ink-secondary">
              Tu solicitud está en revisión. Un administrador la evaluará y recibirás una notificación cuando se
              tome una decisión.
            </p>
          ) : null}
          {request.status === 'REJECTED' && request.decisionReason ? (
            <Alert tone="danger">
              <p className="font-medium">Motivo del rechazo:</p>
              <p className="mt-1 text-sm">{request.decisionReason}</p>
            </Alert>
          ) : null}
          {request.status === 'APPROVED' ? (
            <Alert tone="success">
              Tu solicitud fue aprobada. Ya tienes acceso a las funciones de ANALYST.
            </Alert>
          ) : null}
          {request.status === 'REVOKED' && request.decisionReason ? (
            <Alert tone="danger">
              <p className="font-medium">Motivo de la revocación:</p>
              <p className="mt-1 text-sm">{request.decisionReason}</p>
            </Alert>
          ) : null}
          {request.decidedAt ? (
            <p className="font-mono text-xs tabular-nums text-ink-muted">
              Decisión: {formatDateTime(request.decidedAt)}
              {request.decidedBy ? ` · ${request.decidedBy}` : ''}
            </p>
          ) : null}
        </div>
      </Panel>
    )
  }

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setDone(false)
    setFormError(null)
    if (!form.motivo.trim()) {
      setFormError('El motivo es obligatorio.')
      return
    }
    if (!form.usoPrevisto.trim()) {
      setFormError('El uso previsto es obligatorio.')
      return
    }
    const allChecked = CUESTIONARIO_ITEMS.every((item) => form[item.key])
    if (!allChecked) {
      setFormError('Debes confirmar todos los puntos del cuestionario para continuar.')
      return
    }
    createMutation.mutate(form)
  }

  return (
    <Panel tone="strong">
      <PanelHeader
        title="Solicitud de acceso ANALYST"
        subtitle="Completa el formulario para solicitar acceso a funciones de análisis"
      />
      {done ? (
        <Alert tone="success">
          Solicitud enviada correctamente. Un administrador la revisará y recibirás una notificación.
        </Alert>
      ) : null}
      <form onSubmit={handleSubmit} noValidate className="mt-4 space-y-4">
        {formError ? <Alert tone="danger">{formError}</Alert> : null}
        <div className="grid gap-4 md:grid-cols-2">
          <TextField
            label="Motivo de la solicitud"
            name="motivo"
            required
            value={form.motivo}
            onChange={(event) => setForm((prev) => ({ ...prev, motivo: event.target.value }))}
            placeholder="Explica por qué necesitas acceso ANALYST"
          />
          <TextField
            label="Uso previsto de la plataforma"
            name="usoPrevisto"
            required
            value={form.usoPrevisto}
            onChange={(event) => setForm((prev) => ({ ...prev, usoPrevisto: event.target.value }))}
            placeholder="Describe cómo planeas usar las funciones de análisis"
          />
        </div>
        <fieldset>
          <legend className="label-caps mb-2 border-b border-hairline-subtle pb-1.5">Confirmación de comprensión</legend>
          <div className="divide-y divide-hairline-subtle border-y border-hairline-subtle">
            {CUESTIONARIO_ITEMS.map((item, index) => (
              <label
                key={item.key}
                className="flex cursor-pointer items-start gap-3 px-1 py-2.5 transition-colors hover:bg-surface-2"
              >
                <span aria-hidden="true" className="mt-0.5 font-mono text-[11px] tabular-nums text-brand-strong">{String(index + 1).padStart(2, '0')}</span>
                <input
                  type="checkbox"
                  checked={form[item.key]}
                  onChange={(event) =>
                    setForm((prev) => ({ ...prev, [item.key]: event.target.checked }))
                  }
                  className="mt-0.5 h-4 w-4 shrink-0 rounded-none border-hairline-strong bg-surface-2 accent-brand"
                />
                <span className="text-[13px] leading-normal text-ink-secondary">{item.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <Button type="submit" loading={createMutation.isPending}>
          Enviar solicitud
        </Button>
      </form>
    </Panel>
  )
}
