/**
 * MANDATORY project footer (see frontend/README.md — Footer).
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
      aria-label="Pie de página y aviso legal"
      data-testid="app-footer"
      className={[
        'border-t-2 border-hairline-strong bg-raised',
        className ?? 'mt-auto w-full',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      <div className="mx-auto w-full max-w-content px-4 py-4 sm:px-6">
        <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <nav
            aria-label="Documentos legales y contacto"
            data-testid="footer-legal-nav"
            className="grid max-w-2xl grid-cols-2 gap-x-6 gap-y-1.5 sm:grid-cols-3"
          >
            {LEGAL_DOCUMENTS.map((document) => (
              <a
                key={document.key}
                href={document.path}
                data-testid={`footer-link-${document.key}`}
                className="font-mono text-[11px] uppercase tracking-wide text-ink-muted transition-colors duration-fast hover:text-ink"
              >
                {document.label}
              </a>
            ))}
            <a
              href={`mailto:${LEGAL_CONTACT_EMAIL}`}
              data-testid="footer-contact-link"
              className="font-mono text-[11px] uppercase tracking-wide text-ink-muted transition-colors duration-fast hover:text-ink"
            >
              Contacto
            </a>
            <a
              href={dataRequestHref('Derechos de titular de datos')}
              data-testid="footer-data-request-link"
              className="font-mono text-[11px] uppercase tracking-wide text-ink-muted transition-colors duration-fast hover:text-ink"
            >
              Eliminar o actualizar mis datos
            </a>
          </nav>

          <div className="shrink-0 md:max-w-xs md:text-right">
            <p className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">
              XMR-Forecast · capacidad predictiva evaluada
            </p>
            <p className="mt-0.5 font-mono text-[10px] uppercase tracking-wide text-ink-muted">
              No es asesoría financiera
            </p>
          </div>
        </div>

        <p
          data-testid="app-footer-text"
          className="mt-3 border-t border-hairline-subtle pt-3 font-mono text-[11px] leading-relaxed tracking-wide text-ink-secondary"
        >
          {FOOTER_TEXT}
        </p>
      </div>
    </footer>
  )
}
