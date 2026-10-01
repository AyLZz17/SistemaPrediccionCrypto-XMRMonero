/**
 * Runtime environment contract (R-14, R-33).
 *
 * Only NON-SECRET values may be exposed with the `VITE_` prefix: anything so
 * prefixed is inlined into the public JS bundle. Never add a secret here.
 *
 * The validator hard-fails the application when the API base URL is not served
 * over TLS, so a misconfigured deployment can never fall back to plaintext.
 */

export type RefreshTokenMode = 'body' | 'cookie'

export interface AppEnv {
  /** Absolute https:// origin of the Spring Boot backend. No trailing slash. */
  apiBaseUrl: string
  /**
   * `cookie`  -> backend sets an httpOnly Secure SameSite=Lax refresh cookie;
   *              the browser sends it and JS never sees the token.
   * `body`    -> backend returns refreshToken in the TokenResponse body; the
   *              frontend keeps it in sessionStorage (cleared on tab close).
   */
  refreshTokenMode: RefreshTokenMode
  /** Per-request hard timeout in milliseconds. */
  requestTimeoutMs: number
}

export class EnvValidationError extends Error {
  public readonly problems: string[]

  constructor(problems: string[]) {
    super(
      `Configuracion de entorno invalida:\n - ${problems.join('\n - ')}\n` +
        'Copia .env.example a .env.local y ajusta los valores. Ver frontend/README.md.',
    )
    this.name = 'EnvValidationError'
    this.problems = problems
  }
}

const KNOWN_VITE_VARS = [
  'VITE_API_BASE_URL',
  'VITE_REFRESH_TOKEN_MODE',
  'VITE_REQUEST_TIMEOUT_MS',
  'VITE_DEV_HTTPS_CERT',
  'VITE_DEV_HTTPS_KEY',
] as const

/** Anything that smells like a secret in a VITE_ var is a hard build error. */
const SECRET_NAME_PATTERN = /(SECRET|PASSWORD|PASSWD|TOKEN_KEY|PRIVATE_KEY|API_KEY|CREDENTIAL)/

function isLocalhost(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]' || hostname === '::1'
}

/**
 * Validates an already-resolved environment record.
 * Exported separately so it can be unit-tested without touching import.meta.env.
 */
export function validateEnv(raw: Record<string, string | boolean | undefined>): AppEnv {
  const problems: string[] = []

  for (const [key, value] of Object.entries(raw)) {
    if (!key.startsWith('VITE_')) continue
    // Vercel inyecta metadatos de plataforma (VITE_VERCEL_URL, commit, ...) en
    // el build: no son configuracion de la app ni secretos, se ignoran para
    // que el despliegue no muera por variables que no controlamos.
    if (key.startsWith('VITE_VERCEL_')) continue
    if (!(KNOWN_VITE_VARS as readonly string[]).includes(key)) {
      problems.push(`Variable desconocida "${key}". Solo se admiten: ${KNOWN_VITE_VARS.join(', ')}.`)
    }
    if (SECRET_NAME_PATTERN.test(key)) {
      problems.push(`"${key}" parece un secreto. Nada de secretos en el bundle del frontend (R-14).`)
    }
    if (typeof value === 'string' && /\s/.test(value.trim()) && /secret|token|password/i.test(key)) {
      problems.push(`"${key}" no debe contener credenciales.`)
    }
  }

  const baseUrl = typeof raw.VITE_API_BASE_URL === 'string' ? raw.VITE_API_BASE_URL.trim() : ''

  if (!baseUrl) {
    problems.push('VITE_API_BASE_URL es obligatoria (origen https:// del backend Spring Boot).')
  } else {
    let parsed: URL | null = null
    try {
      parsed = new URL(baseUrl)
    } catch {
      problems.push(`VITE_API_BASE_URL no es una URL absoluta: "${baseUrl}".`)
    }

    if (parsed) {
      if (parsed.protocol !== 'https:') {
        problems.push(
          `VITE_API_BASE_URL debe usar https:// (recibido "${parsed.protocol}//"). ` +
            'HTTPS esta forzado en todos los entornos (R-33).',
        )
      }
      if (parsed.username !== '' || parsed.password !== '') {
        problems.push('VITE_API_BASE_URL no debe incluir credenciales embebidas (R-14).')
      }
      if (parsed.search !== '' || parsed.hash !== '') {
        problems.push('VITE_API_BASE_URL no debe incluir query string ni fragmento.')
      }
      // http://localhost would already be rejected; keep an explicit note for clarity.
      if (!isLocalhost(parsed.hostname) && parsed.hostname === 'localhost') {
        problems.push('Host local no permitido.')
      }
    }
  }

  const modeRaw = raw.VITE_REFRESH_TOKEN_MODE
  const refreshTokenMode: RefreshTokenMode = modeRaw === 'cookie' ? 'cookie' : 'body'
  if (modeRaw !== undefined && modeRaw !== '' && modeRaw !== 'cookie' && modeRaw !== 'body') {
    problems.push(`VITE_REFRESH_TOKEN_MODE admite solo "body" o "cookie" (recibido "${String(modeRaw)}").`)
  }

  const timeoutRaw = raw.VITE_REQUEST_TIMEOUT_MS
  let requestTimeoutMs = 20_000
  if (timeoutRaw !== undefined && String(timeoutRaw) !== '') {
    const parsedTimeout = Number(String(timeoutRaw))
    if (!Number.isFinite(parsedTimeout) || parsedTimeout < 1000 || parsedTimeout > 120_000) {
      problems.push('VITE_REQUEST_TIMEOUT_MS debe ser un numero entre 1000 y 120000.')
    } else {
      requestTimeoutMs = parsedTimeout
    }
  }

  if (problems.length > 0) {
    throw new EnvValidationError(problems)
  }

  return {
    apiBaseUrl: baseUrl.replace(/\/+$/, ''),
    refreshTokenMode,
    requestTimeoutMs,
  }
}

let cached: AppEnv | null = null

/** Resolves and caches the validated environment. Throws EnvValidationError. */
export function getEnv(): AppEnv {
  if (cached) return cached
  cached = validateEnv(import.meta.env as unknown as Record<string, string | boolean | undefined>)
  return cached
}

/** Test seam: forces the next getEnv() call to re-read import.meta.env. */
export function resetEnvCache(): void {
  cached = null
}

export function buildApiUrl(path: string): string {
  const base = getEnv().apiBaseUrl
  if (/^https?:\/\//i.test(path)) return path
  return `${base}${path.startsWith('/') ? '' : '/'}${path}`
}
