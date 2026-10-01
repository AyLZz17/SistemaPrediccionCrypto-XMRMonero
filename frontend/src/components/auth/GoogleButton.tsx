import { useMemo } from 'react'
import { googleAuthorizeUrl, type GoogleConsentParams } from '../../api/auth'

/**
 * "Continuar con Google" trigger.
 *
 * The whole OAuth dance is backend-driven (R-35, no secret in the bundle): we
 * simply point the browser at the Spring Boot authorize endpoint, which answers
 * 302 to Google and eventually returns to `/auth/callback`.
 *
 * It is a real anchor (not a JS-driven navigation) so it is keyboard
 * accessible, announces as a link, and keeps middle-click / open-in-new-tab.
 */
export function GoogleButton({
  returnTo = '/dashboard',
  label,
  consent,
  variant = 'block',
  testId = 'google-oauth-button',
}: {
  returnTo?: string
  /** Defaults to "Continuar con Google"; the compact header uses its own. */
  label?: string
  /**
   * Consent ticks gathered on OUR page before leaving for Google. The backend
   * stores them inside the one-time `state`; without them the callback will
   * still sign an existing account in, but it will NOT create a new one and
   * answers `CONSENT_REQUIRED` instead.
   */
  consent?: GoogleConsentParams
  /** `block` for forms, `compact` for the public header (dense, short). */
  variant?: 'block' | 'compact'
  /**
   * The header renders a second trigger on the same pages as the form one, so
   * each keeps its own id: tests must be able to address one without the other.
   */
  testId?: string
}) {
  const compact = variant === 'compact'
  const text = label ?? (compact ? 'Iniciar sesión con Google' : 'Continuar con Google')
  const href = useMemo(() => {
    try {
      return googleAuthorizeUrl(returnTo, consent)
    } catch {
      // A misconfigured env must not leave a dead link without explanation.
      return '#configuracion-invalida'
    }
  }, [returnTo, consent])

  return (
    <a
      href={href}
      data-testid={testId}
      aria-label={compact ? text : undefined}
      className={
        compact
          ? 'flex items-center gap-2 rounded-md border border-hairline bg-surface-2 px-3 py-2 text-xs font-medium text-ink transition-colors duration-fast hover:border-hairline-strong hover:bg-surface-3'
          : 'flex h-10 w-full items-center justify-center gap-2.5 rounded-md border border-hairline bg-surface-2 px-4 text-sm font-medium text-ink transition-all duration-fast ease-out hover:border-hairline-strong hover:bg-surface-3'
      }
    >
      <svg viewBox="0 0 18 18" className="h-4 w-4 shrink-0" aria-hidden="true">
        <path
          fill="#4285F4"
          d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.71-1.57 2.68-3.88 2.68-6.62z"
        />
        <path
          fill="#34A853"
          d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.81.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"
        />
        <path fill="#FBBC05" d="M3.97 10.72a5.4 5.4 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3.01-2.33z" />
        <path
          fill="#EA4335"
          d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.9 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"
        />
      </svg>
      <span className="truncate">{text}</span>
    </a>
  )
}
