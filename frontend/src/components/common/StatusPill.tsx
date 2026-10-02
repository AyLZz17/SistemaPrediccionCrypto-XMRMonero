import { Badge, type BadgeTone } from '../ui'

/**
 * Maps a backend status string to a tone + human label.
 * Unknown values degrade to the neutral tone instead of throwing.
 */
const TONE_BY_STATUS: Record<string, BadgeTone> = {
  // jobs
  QUEUED: 'neutral',
  RUNNING: 'active',
  SUCCEEDED: 'success',
  SUCCESS: 'success',
  FAILED: 'danger',
  FAILURE: 'danger',
  CANCELLED: 'warning',
  // predictions
  READY: 'success',
  PENDING: 'warning',
  // roles (roles are metadata, not state: no alert colours)
  ADMIN: 'neutral',
  ANALYST: 'neutral',
  VIEWER: 'neutral',
  // outcomes
  UP: 'success',
  DOWN: 'danger',
  FLAT: 'neutral',
}

const LABEL_BY_STATUS: Record<string, string> = {
  QUEUED: 'en cola',
  RUNNING: 'ejecutando',
  SUCCEEDED: 'completado',
  SUCCESS: 'ok',
  FAILED: 'fallido',
  FAILURE: 'fallo',
  CANCELLED: 'cancelado',
  READY: 'listo',
  PENDING: 'pendiente',
  ADMIN: 'admin',
  ANALYST: 'analyst',
  VIEWER: 'viewer',
  UP: 'sube',
  DOWN: 'baja',
  FLAT: 'estable',
}

export function statusTone(status: string | null | undefined): BadgeTone {
  if (!status) return 'neutral'
  return TONE_BY_STATUS[status.toUpperCase()] ?? 'neutral'
}

export function statusLabel(status: string | null | undefined, fallback?: string): string {
  if (!status) return fallback ?? '—'
  return LABEL_BY_STATUS[status.toUpperCase()] ?? fallback ?? status
}

export function StatusPill({
  status,
  label,
  pulse,
}: {
  status: string | null | undefined
  label?: string
  pulse?: boolean
}) {
  const text = statusLabel(status, label)
  const live = status === 'RUNNING' || status === 'QUEUED'
  return (
    <Badge tone={statusTone(status)} dot pulse={pulse ?? live} className="shrink-0">
      {text}
    </Badge>
  )
}