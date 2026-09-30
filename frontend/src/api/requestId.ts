/**
 * Correlation identifiers.
 *
 * Every outbound request carries `X-Request-Id` so the Spring Boot backend, and
 * through it the ML service, can be traced from a UI error toast (R-26/R-32).
 * A server-provided `X-Request-Id` always wins over the client-generated one.
 */

const REQUEST_ID_RE = /^[0-9a-zA-Z-]{8,128}$/

let counter = 0

export function newRequestId(): string {
  const cryptoRef: Crypto | undefined = globalThis.crypto
  if (cryptoRef && typeof cryptoRef.randomUUID === 'function') {
    return cryptoRef.randomUUID()
  }
  if (cryptoRef && typeof cryptoRef.getRandomValues === 'function') {
    const bytes = cryptoRef.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6]! & 0x0f) | 0x40
    bytes[8] = (bytes[8]! & 0x3f) | 0x80
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  // Last resort; still unique per process.
  counter += 1
  return `req-${Date.now().toString(36)}-${counter.toString(36)}`
}

export function isRequestId(value: unknown): value is string {
  return typeof value === 'string' && REQUEST_ID_RE.test(value)
}

/** Prefer the id echoed by the server, otherwise keep the one we sent. */
export function resolveRequestId(serverValue: string | null, fallback: string): string {
  return serverValue && isRequestId(serverValue) ? serverValue : fallback
}
