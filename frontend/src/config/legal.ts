/**
 * Single source of truth for the legal documents on the client (R-43).
 *
 * The backend exposes the same values in `GET /api/v1/meta/legal`, and
 * `LegalVersionsContractTest` reads BOTH files and fails if they ever drift:
 * a version shown to the user must be the version recorded in
 * `consent_records`, otherwise the proof of acceptance points at a text nobody
 * saw.
 *
 * Editing the version here means editing it in
 * `backend/.../common/LegalDocuments.java` too — the test says so.
 */

/** Version of every document published today. */
export const LEGAL_VERSION = '2026-10-01'

/** Date the current texts took effect (equals the version by convention). */
export const LEGAL_EFFECTIVE_DATE = '2026-10-01'

/** Operational mailbox for data-subject requests and contact. */
export const LEGAL_CONTACT_EMAIL = 'aylzz.software.solutions@gmail.com'

export interface LegalDocumentLink {
  key: string
  label: string
  path: string
}

/** Documents in the order the footer lists them. */
export const LEGAL_DOCUMENTS: LegalDocumentLink[] = [
  { key: 'terms', label: 'Terminos y condiciones', path: '/terms' },
  { key: 'privacy', label: 'Politica de privacidad', path: '/privacy' },
  { key: 'dataPolicy', label: 'Tratamiento de datos personales', path: '/data-policy' },
  { key: 'cookies', label: 'Politica de cookies', path: '/cookies' },
  { key: 'legalNotice', label: 'Aviso legal', path: '/legal-notice' },
]

/** `mailto:` for requesting deletion, correction or access to personal data. */
export function dataRequestHref(subject: string): string {
  return `mailto:${LEGAL_CONTACT_EMAIL}?subject=${encodeURIComponent(subject)}`
}
