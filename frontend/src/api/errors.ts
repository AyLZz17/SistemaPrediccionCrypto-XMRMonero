/**
 * Uniform error model for the Spring Boot backend.
 *
 * Backend error envelope (contract):
 *   { timestamp, status, error, code, message, path, requestId, fieldErrors? }
 *
 * Every failure surfaced by `apiClient` is an `ApiError`, so pages can render a
 * friendly, status-specific message plus the `requestId` needed to correlate
 * frontend -> backend -> ML service in the logs (R-26/R-32).
 */

export interface FieldError {
  field: string
  message: string
}

export interface ApiErrorBody {
  timestamp?: string
  status?: number
  error?: string
  code?: string
  message?: string
  path?: string
  requestId?: string
  fieldErrors?: FieldError[]
}

export type ApiErrorKind = 'network' | 'timeout' | 'aborted' | 'http'

/** Distinct, user-facing copy per failure class (never a raw stack trace). */
const MESSAGES: Record<number, string> = {
  400: 'La solicitud no es valida. Revisa los campos marcados e intentalo de nuevo.',
  401: 'Tu sesion expiro o no es valida. Vuelve a iniciar sesion.',
  403: 'No tienes permisos para realizar esta accion. Contacta con un administrador si crees que es un error.',
  404: 'No encontramos el recurso solicitado.',
  405: 'La operacion no esta permitida sobre este recurso.',
  409: 'El recurso ya existe o entra en conflicto con el estado actual.',
  413: 'La carga supera el tamano maximo permitido.',
  422: 'Los datos enviados no superar la validacion del servidor.',
  429: 'Demasiadas solicitudes seguidas. Espera unos segundos e intentalo de nuevo.',
  500: 'El servidor encontro un error inesperado. Intentalo de nuevo en unos minutos.',
  501: 'Esta funcionalidad todavia no esta disponible en el servidor.',
  502: 'La puerta de enlace del backend no respondio correctamente. Intentalo de nuevo.',
  503: 'El servicio no esta disponible temporalmente. Intentalo de nuevo en unos minutos.',
  504: 'El servidor tardo demasiado en responder. Intentalo de nuevo.',
}

const NETWORK_MESSAGE =
  'No pudimos conectar con el servidor. Revisa tu conexion e intentalo de nuevo.'
const TIMEOUT_MESSAGE =
  'El servidor tardo demasiado en responder. Se aborto la solicitud para no dejar la interfaz colgada.'

export class ApiError extends Error {
  public readonly kind: ApiErrorKind
  public readonly status: number | null
  public readonly code: string | null
  public readonly path: string | null
  public readonly requestId: string | null
  public readonly fieldErrors: FieldError[]
  public readonly friendlyMessage: string

  constructor(init: {
    kind: ApiErrorKind
    status?: number | null
    code?: string | null
    message?: string
    friendlyMessage?: string
    path?: string | null
    requestId?: string | null
    fieldErrors?: FieldError[]
  }) {
    const status = init.status ?? null
    const friendly =
      init.friendlyMessage ??
      (init.kind === 'timeout'
        ? TIMEOUT_MESSAGE
        : init.kind === 'network'
          ? NETWORK_MESSAGE
          : status !== null
            ? (MESSAGES[status] ?? 'Se produjo un error inesperado. Intentalo de nuevo.')
            : 'Se produjo un error inesperado. Intentalo de nuevo.')

    super(friendly)

    this.name = 'ApiError'
    this.kind = init.kind
    this.status = status
    this.code = init.code ?? null
    this.path = init.path ?? null
    this.requestId = init.requestId ?? null
    this.fieldErrors = init.fieldErrors ?? []
    this.friendlyMessage = friendly
  }

  /** True when the caller may reasonably retry the very same request. */
  get retryable(): boolean {
    if (this.kind === 'timeout' || this.kind === 'network') return true
    if (this.status === null) return false
    return this.status === 429 || this.status === 500 || this.status === 502 || this.status === 503 || this.status === 504
  }

  /** True when the user should be sent back to the login screen. */
  get unauthorized(): boolean {
    return this.status === 401
  }

  get forbidden(): boolean {
    return this.status === 403
  }

  get rateLimited(): boolean {
    return this.status === 429
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

/** Normalises anything thrown by the client into an ApiError. */
export function toApiError(value: unknown, fallbackPath: string | null = null): ApiError {
  if (isApiError(value)) return value
  if (value instanceof Error) {
    return new ApiError({ kind: 'network', message: value.message, path: fallbackPath })
  }
  return new ApiError({ kind: 'network', message: String(value), path: fallbackPath })
}

export function parseErrorBody(raw: unknown): ApiErrorBody {
  if (typeof raw !== 'object' || raw === null) return {}
  const body = raw as Record<string, unknown>
  const fieldErrors: FieldError[] = Array.isArray(body.fieldErrors)
    ? body.fieldErrors.flatMap((entry) => {
        if (typeof entry !== 'object' || entry === null) return []
        const item = entry as Record<string, unknown>
        const field = typeof item.field === 'string' ? item.field : undefined
        const message = typeof item.message === 'string' ? item.message : undefined
        if (field === undefined && message === undefined) return []
        return [{ field: field ?? 'global', message: message ?? 'Valor no valido.' }]
      })
    : []
  return {
    timestamp: typeof body.timestamp === 'string' ? body.timestamp : undefined,
    status: typeof body.status === 'number' ? body.status : undefined,
    error: typeof body.error === 'string' ? body.error : undefined,
    code: typeof body.code === 'string' ? body.code : undefined,
    message: typeof body.message === 'string' ? body.message : undefined,
    path: typeof body.path === 'string' ? body.path : undefined,
    requestId: typeof body.requestId === 'string' ? body.requestId : undefined,
    fieldErrors,
  }
}

/** Human summary used by toasts and the <Alert> error states. */
export function describeError(error: unknown): string {
  const apiError = toApiError(error)
  const suffix = apiError.requestId ? ` (requestId: ${apiError.requestId})` : ''
  return `${apiError.friendlyMessage}${suffix}`
}
