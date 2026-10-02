/**
 * Product mark: a solid institutional-red square with a light ledger line.
 * The only saturated red fill in the interface besides primary buttons.
 */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex shrink-0 items-center justify-center rounded-sm bg-brand-solid text-ink-inverse"
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 24 24" width={size * 0.62} height={size * 0.62} fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
        <path d="M3 17.5 7.5 11l3.5 3.6L15 7l6 10.5" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="15" cy="7" r="1.6" fill="currentColor" stroke="none" />
        <path d="M3 21h18" strokeLinecap="round" opacity="0.55" />
      </svg>
    </span>
  )
}
