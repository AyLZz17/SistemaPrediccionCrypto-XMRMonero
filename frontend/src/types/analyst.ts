/** Solicitudes de acceso al rol ANALYST. */

export type AnalystAccessRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'

export interface AnalystAccessRequest {
  id: string
  motivo: string
  usoPrevisto: string
  cuestionarioVersion: string
  status: AnalystAccessRequestStatus
  createdAt: string
  decidedAt: string | null
  decisionReason: string | null
  decidedBy: string | null
}

export interface CreateAnalystAccessRequest {
  motivo: string
  usoPrevisto: string
  aceptaRiesgos: boolean
  aceptaLimitaciones: boolean
  aceptaMetricas: boolean
  aceptaNoGarantia: boolean
  aceptaNoOperaciones: boolean
  aceptaNoBacktesting: boolean
  aceptaRolAnalyst: boolean
  aceptaNoRentabilidad: boolean
}

export const CUESTIONARIO_VERSION = '2026-10-01'

type ConsentKey = keyof Pick<
  CreateAnalystAccessRequest,
  | 'aceptaRiesgos'
  | 'aceptaLimitaciones'
  | 'aceptaMetricas'
  | 'aceptaNoGarantia'
  | 'aceptaNoOperaciones'
  | 'aceptaNoBacktesting'
  | 'aceptaRolAnalyst'
  | 'aceptaNoRentabilidad'
>

export const CUESTIONARIO_ITEMS: { key: ConsentKey; label: string }[] = [
  { key: 'aceptaRiesgos', label: 'XMR-Forecast no es asesoría financiera.' },
  { key: 'aceptaLimitaciones', label: 'Las predicciones son capacidad predictiva evaluada sobre datos históricos.' },
  { key: 'aceptaMetricas', label: 'Los resultados no garantizan rentabilidad ni resultados futuros.' },
  { key: 'aceptaNoGarantia', label: 'Las métricas pueden cambiar ante volatilidad, cambios bruscos de mercado y picos.' },
  { key: 'aceptaNoOperaciones', label: 'Debe revisar MAE, RMSE, MAPE, dirección y desviación entre semillas.' },
  { key: 'aceptaNoBacktesting', label: 'El modelo campeón se selecciona por validación y el test solo se reporta.' },
  { key: 'aceptaRolAnalyst', label: 'No se deben interpretar los resultados como instrucciones de compra o venta.' },
  { key: 'aceptaNoRentabilidad', label: 'La plataforma no realiza operaciones ni backtesting de trading.' },
]
