import { describe, expect, it } from 'vitest'
import { EnvValidationError, validateEnv } from '../config/env'

const VALID = { VITE_API_BASE_URL: 'https://api.xmr-forecast.example' }

describe('env validator', () => {
  it('accepts a well-formed https base URL', () => {
    expect(validateEnv(VALID)).toMatchObject({
      apiBaseUrl: 'https://api.xmr-forecast.example',
      refreshTokenMode: 'body',
      requestTimeoutMs: 20_000,
    })
  })

  it('strips trailing slashes', () => {
    expect(validateEnv({ VITE_API_BASE_URL: 'https://api.example/' }).apiBaseUrl).toBe('https://api.example')
  })

  it('accepts https://localhost for development', () => {
    expect(validateEnv({ VITE_API_BASE_URL: 'https://localhost:8443' }).apiBaseUrl).toBe('https://localhost:8443')
  })

  it('hard-fails on plain http', () => {
    expect(() => validateEnv({ VITE_API_BASE_URL: 'http://api.example' })).toThrow(EnvValidationError)
    try {
      validateEnv({ VITE_API_BASE_URL: 'http://api.example' })
    } catch (error) {
      expect((error as EnvValidationError).problems.join(' ')).toMatch(/https:\/\//)
    }
  })

  it('hard-fails on http://localhost too (R-33 requires TLS everywhere)', () => {
    expect(() => validateEnv({ VITE_API_BASE_URL: 'http://localhost:8080' })).toThrow(/debe usar https/)
  })

  it('hard-fails when the variable is missing', () => {
    expect(() => validateEnv({})).toThrow(/VITE_API_BASE_URL es obligatoria/)
  })

  it('hard-fails on a non-absolute URL', () => {
    expect(() => validateEnv({ VITE_API_BASE_URL: '/api' })).toThrow(/no es una URL absoluta/)
  })

  it('rejects embedded credentials', () => {
    expect(() => validateEnv({ VITE_API_BASE_URL: 'https://user:pass@api.example' })).toThrow(
      /no debe incluir credenciales embebidas/,
    )
  })

  it('rejects query strings and fragments', () => {
    expect(() => validateEnv({ VITE_API_BASE_URL: 'https://api.example?a=1' })).toThrow(/query string/)
    expect(() => validateEnv({ VITE_API_BASE_URL: 'https://api.example#x' })).toThrow(/query string/)
  })

  it('rejects an unknown VITE_ variable', () => {
    expect(() => validateEnv({ ...VALID, VITE_SOMETHING_ELSE: 'x' })).toThrow(/Variable desconocida/)
  })

  it('rejects anything that looks like a secret in the bundle (R-14)', () => {
    expect(() => validateEnv({ ...VALID, VITE_GOOGLE_CLIENT_SECRET: 'shh' })).toThrow(/parece un secreto/)
    expect(() => validateEnv({ ...VALID, VITE_DB_PASSWORD: 'x' })).toThrow(/parece un secreto/)
  })

  it('validates the refresh token mode', () => {
    expect(validateEnv({ ...VALID, VITE_REFRESH_TOKEN_MODE: 'cookie' }).refreshTokenMode).toBe('cookie')
    expect(() => validateEnv({ ...VALID, VITE_REFRESH_TOKEN_MODE: 'localStorage' })).toThrow(
      /admite solo "body" o "cookie"/,
    )
  })

  it('validates the timeout bounds', () => {
    expect(validateEnv({ ...VALID, VITE_REQUEST_TIMEOUT_MS: '5000' }).requestTimeoutMs).toBe(5000)
    expect(() => validateEnv({ ...VALID, VITE_REQUEST_TIMEOUT_MS: '10' })).toThrow(/entre 1000 y 120000/)
    expect(() => validateEnv({ ...VALID, VITE_REQUEST_TIMEOUT_MS: '999999' })).toThrow(/entre 1000 y 120000/)
    expect(() => validateEnv({ ...VALID, VITE_REQUEST_TIMEOUT_MS: 'abc' })).toThrow(/entre 1000 y 120000/)
  })

  it('does not reject non-VITE variables such as NODE_ENV', () => {
    expect(() => validateEnv({ ...VALID, NODE_ENV: 'production' })).not.toThrow()
  })
})
