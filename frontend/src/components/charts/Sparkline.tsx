import { useId } from 'react'

/**
 * Dependency-free sparkline (no chart library in the critical path) so the
 * dashboard stays light. Purely decorative: the accessible summary lives in
 * the visually hidden caption.
 */
export function Sparkline({
  values,
  width = 220,
  height = 48,
  stroke = 'var(--xmr-text-secondary)',
  label = 'Tendencia',
}: {
  values: number[]
  width?: number
  height?: number
  stroke?: string
  label?: string
}) {
  const titleId = useId()
  if (values.length < 2) {
    return <div className="h-12 rounded-md border border-hairline-subtle bg-surface-inset" aria-hidden="true" />
  }

  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const stepX = width / (values.length - 1)
  const points = values
    .map((value, index) => {
      const x = index * stepX
      const y = height - ((value - min) / span) * (height - 6) - 3
      return `${x.toFixed(2)},${y.toFixed(2)}`
    })
    .join(' ')

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-12 w-full"
      preserveAspectRatio="none"
      role="img"
      aria-labelledby={titleId}
    >
      <title id={titleId}>
        {label}: mínimo {min}, máximo {max}
      </title>
      <polyline
        points={points}
        fill="none"
        stroke={stroke}
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}