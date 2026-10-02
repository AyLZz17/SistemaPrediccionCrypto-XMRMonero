/**
 * Product mark: a charcoal plate with a red OHLC candlestick glyph —
 * the terminal mark of the platform.
 */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex shrink-0 items-center justify-center rounded-sm border border-hairline-default bg-elevated"
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 24 24" width={size * 0.62} height={size * 0.62} fill="none" aria-hidden="true">
        <path d="M6 3v18M12 6v12M18 2v20" stroke="var(--xmr-text-muted)" strokeWidth="1.4" strokeLinecap="round" />
        <path d="M4 8h4v6H4zM10 9h4v7h-4zM16 7h4v5h-4z" fill="var(--xmr-brand)" />
      </svg>
    </span>
  )
}
