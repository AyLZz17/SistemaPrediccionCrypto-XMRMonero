/**
 * MANDATORY project footer (see frontend/README.md § Footer).
 *
 * The exact string below is a contractual requirement and must appear, character
 * for character, on EVERY route and every visible state of the application.
 * It is rendered by:
 *   - `PublicLayout`      (landing, login, register, forgot/reset password, Google callback,
 *                          verification and the five legal documents)
 *   - `AuthenticatedLayout` (dashboard, market, predictions, experiments, metrics,
 *                            models, jobs, account, admin, audit, logs)
 *   - `NotFoundPage`      (the `*` route)
 *   - `ErrorBoundary`     (unhandled render errors)
 *
 * A dedicated test (`footer-on-every-route.test.tsx`) asserts the literal text
 * on every route so a future refactor cannot silently drop it.
 *
 * Besides the copyright it carries the legal navigation required by the
 * project: terms, privacy, data-treatment policy, cookies, legal notice,
 * contact and the data deletion / update request link. They live here (and not
 * in a layout) so no screen can ship without them.
 *
 * The links are plain `<a href>` on purpose: `ErrorBoundary` sits ABOVE
 * `BrowserRouter`, so its fallback must render without router context. A `<Link>`
 * here would crash the crash screen — the footer is exactly the element that
 * has to survive everything.
 *
 * `Sistema esta realizado por (c) AyLZz17 - AyLZz Software Solutions. Todos los
 * derechos reservados.` — written without accents and with the explicit U+00A9
 * escape so no source-encoding change can alter the rendered characters.
 */
import { LEGAL_CONTACT_EMAIL, LEGAL_DOCUMENTS, dataRequestHref } from '../../config/legal'

export const FOOTER_TEXT =
  'Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.'

export function Footer({ className }: { className?: string }) {
  return (
    <footer
      aria-label="Pie de pagina y aviso legal"
      data-testid="app-footer"
      className={[
        'border-t border-hairline-subtle bg-surface-inset/80',
        className ?? 'mt-auto w-full',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <div className="mx-auto w-full max-w-content px-4 py-4 sm:px-6">
        <nav
          aria-label="Documentos legales y contacto"
          data-testid="footer-legal-nav"
          className="flex flex-wrap items-center justify-center gap-x-4 gap-y-2 border-b border-hairline-subtle pb-3 sm:justify-start"
        >
          {LEGAL_DOCUMENTS.map((document) => (
            <a
              key={document.key}
              href={document.path}
              data-testid={`footer-link-${document.key}`}
              className="text-xs text-ink-secondary underline-offset-2 transition-colors duration-fast hover:text-ink hover:underline"
            >
              {document.label}
            </a>
          ))}
          <a
            href={`mailto:${LEGAL_CONTACT_EMAIL}`}
            data-testid="footer-contact-link"
            className="text-xs text-ink-secondary underline-offset-2 transition-colors duration-fast hover:text-ink hover:underline"
          >
            Contacto
          </a>
          <a
            href={dataRequestHref('Derechos de titular de datos')}
            data-testid="footer-data-request-link"
            className="text-xs text-ink-secondary underline-offset-2 transition-colors duration-fast hover:text-ink hover:underline"
          >
            Eliminar o actualizar mis datos
          </a>
        </nav>

        <div className="mt-3 flex flex-col items-center justify-between gap-2 text-center sm:flex-row sm:text-left">
          <p
            data-testid="app-footer-text"
            className="font-mono text-xs leading-relaxed tracking-wide text-ink-secondary"
          >
            {FOOTER_TEXT}
          </p>
          <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">
            XMR-Forecast · capacidad predictiva evaluada · no es asesoria financiera
          </p>
        </div>
      </div>
    </footer>
  )
}
