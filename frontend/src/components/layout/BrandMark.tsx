export function BrandMark({ size = 32 }: { size?: number }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex shrink-0 items-center justify-center rounded-md border border-accent-cyan/40 bg-accent-cyan-soft shadow-glow-cyan"
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 24 24" width={size * 0.62} height={size * 0.62} fill="none" stroke="currentColor" strokeWidth="1.8" className="text-accent-cyan">
        <path d="M3 17.5 7.5 11l3.5 3.6L15 7l6 10.5" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="15" cy="7" r="1.6" fill="currentColor" stroke="none" />
        <path d="M3 21h18" strokeLinecap="round" opacity="0.45" />
      </svg>
    </span>
  )
}
