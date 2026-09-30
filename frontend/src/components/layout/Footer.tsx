/**
 * MANDATORY project footer (see frontend/README.md § Footer).
 *
 * The exact string below is a contractual requirement and must appear, character
 * for character, on EVERY route and every visible state of the application.
 * It is rendered by:
 *   - `PublicLayout`      (landing, login, register, forgot/reset password, Google callback)
 *   - `AuthenticatedLayout` (dashboard, market, predictions, experiments, metrics,
 *                            models, jobs, account, admin, audit, logs)
 *   - `NotFoundPage`      (the `*` route)
 *   - `ErrorBoundary`     (unhandled render errors)
 *
 * A dedicated test (`footer-on-every-route.test.tsx`) asserts the literal text
 * on every route so a future refactor cannot silently drop it.
 *
 * `Sistema esta realizado por (c) AyLZz17 - AyLZz Software Solutions. Todos los
 * derechos reservados.` — written without accents and with the explicit U+00A9
 * escape so no source-encoding change can alter the rendered characters.
 */
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
      <div className="mx-auto flex w-full max-w-content flex-col items-center justify-between gap-2 px-4 py-4 text-center sm:flex-row sm:px-6 sm:text-left">
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
    </footer>
  )
}
