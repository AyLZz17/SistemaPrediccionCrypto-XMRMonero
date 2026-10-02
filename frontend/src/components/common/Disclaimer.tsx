/**
 * R-11 / R-12 — legal and ethical notice.
 *
 * The product is NOT financial advice, promises no profitability, and never
 * simulates trades or backtests. The wording deliberately uses "capacidad
 * predictiva evaluada" and never "predice el mercado" (R-12).
 *
 * Rendered on: landing, dashboard, predictions and metrics. It is also part of
 * the login and register pages, which is why it lives in its own component.
 */

export const DISCLAIMER_FULL =
  'XMR-Forecast muestra resultados de una capacidad predictiva evaluada sobre datos historicos de Monero (XMR-USD). ' +
  'No es asesoria financiera, no ofrece ni promete rentabilidad, y no simula operaciones ni backtesting de trading. ' +
  'Las metricas mostradas proceden de particiones cronologicas de evaluacion y pueden no repetirse en el futuro.'

export const DISCLAIMER_SHORT =
  'Capacidad predictiva evaluada. No es asesoria financiera ni promete rentabilidad. No se simulan operaciones.'

export type DisclaimerVariant = 'full' | 'short' | 'inline'

export function Disclaimer({
  variant = 'full',
  className,
}: {
  variant?: DisclaimerVariant
  className?: string
}) {
  if (variant === 'inline') {
    return (
      <p data-testid="disclaimer" className={['text-xs leading-relaxed text-ink-muted', className].filter(Boolean).join(' ')}>
        {DISCLAIMER_SHORT}
      </p>
    )
  }

  return (
    <aside
      data-testid="disclaimer"
      aria-label="Aviso legal: no es asesoria financiera"
      className={['rounded border border-hairline bg-surface-inset px-4 py-3', className].filter(Boolean).join(' ')}
    >
      <p className="mb-1.5 flex items-center gap-2 font-mono text-[11px] uppercase tracking-wide text-accent-amber">
        <span aria-hidden="true" className="inline-block h-3 w-0.5 bg-accent-amber" />
        Aviso legal · R-11
      </p>
      <p className="text-[13px] leading-relaxed text-ink-secondary">
        {variant === 'full' ? DISCLAIMER_FULL : DISCLAIMER_SHORT}
      </p>
    </aside>
  )
}
