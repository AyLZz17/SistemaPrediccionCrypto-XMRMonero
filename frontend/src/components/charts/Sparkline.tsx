import { useId } from 'react'

/**
 * Dependency-free trend line (no chart library in the critical path) so the
 * dashboard stays light. The accessible summary lives in the `<title>`, with
 * min/max/first/last values; a square end marker shows the latest value.
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
    return <div className="h-12 border border-hairline-subtle bg-surface-inset" aria-hidden="true" />
  }

  const min = Math.min(...values)
  const max = Math.max(...values)
  const first = values[0]!
  const last = values[values.length - 1]!
  const span = max - min || 1
  const stepX = width / (values.length - 1)
  const coords = values.map((value, index) => {
    const x = index * stepX
    const y = height - ((value - min) / span) * (height - 6) - 3
    return { x, y }
  })
  const points = coords.map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ')
  const end = coords[coords.length - 1]!

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="h-12 w-full"
      preserveAspectRatio="none"
      role="img"
      aria-labelledby={titleId}
    >
      <title id={titleId}>
        {label}: mínimo {min}, máximo {max}, primero {first}, último {last}
      </title>
      <polyline
        points={points}
        fill="none"
        stroke={stroke}
        strokeWidth="1.6"
        strokeLinecap="square"
        strokeLinejoin="miter"
        vectorEffect="non-scaling-stroke"
      />
      <rect
        x={(end.x - 2).toFixed(2)}
        y={(end.y - 2).toFixed(2)}
        width="4"
        height="4"
        fill={stroke}
        aria-hidden="true"
      />
    </svg>
  )
}
