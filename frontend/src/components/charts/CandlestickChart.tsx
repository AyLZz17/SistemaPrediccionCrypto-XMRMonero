import { useMemo } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { Candle } from '../../types'

const UP = 'var(--xmr-accent-green)'
const DOWN = 'var(--xmr-accent-red)'

interface ChartDatum {
  date: string
  range: number
  body: number
  fill: string
}

/** OHLC -> wick (high-low) + body (|close-open|), coloured by direction. */
export function CandlestickChart({ candles }: { candles: Candle[] }) {
  const data = useMemo<ChartDatum[]>(
    () =>
      candles.slice(-120).map((candle) => ({
        date: candle.date.slice(5),
        range: Math.max(candle.high - candle.low, 0.01),
        body: Math.max(Math.abs(candle.close - candle.open), 0.01),
        fill: candle.close >= candle.open ? UP : DOWN,
      })),
    [candles],
  )

  if (data.length === 0) return null

  return (
    <div className="h-72 w-full" role="img" aria-label="Grafico de velas japonesas de XMR-USD">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--xmr-border-subtle)" vertical={false} />
          <XAxis
            dataKey="date"
            tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
            axisLine={{ stroke: 'var(--xmr-border-default)' }}
            tickLine={false}
            minTickGap={24}
          />
          <YAxis
            tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
            axisLine={false}
            tickLine={false}
            width={56}
          />
          <Tooltip
            cursor={{ fill: 'var(--xmr-surface-2)' }}
            contentStyle={{
              background: 'var(--xmr-surface-2)',
              border: '1px solid var(--xmr-border-default)',
              borderRadius: 'var(--xmr-radius-md)',
              fontFamily: 'var(--xmr-font-mono)',
              fontSize: 12,
              color: 'var(--xmr-text-primary)',
            }}
          />
          <Bar dataKey="range" fill="var(--xmr-border-default)" radius={[2, 2, 0, 0]} isAnimationActive={false} />
          <Bar dataKey="body" radius={[2, 2, 0, 0]} isAnimationActive={false}>
            {data.map((entry) => (
              <Cell key={`${entry.date}-${entry.fill}`} fill={entry.fill} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div className="mt-2 flex items-center justify-end gap-4">
        <span className="flex items-center gap-1.5 font-mono text-[11px] text-ink-muted">
          <span aria-hidden="true" className="h-2 w-2 rounded-sm" style={{ background: UP }} /> cierre &gt;= apertura
        </span>
        <span className="flex items-center gap-1.5 font-mono text-[11px] text-ink-muted">
          <span aria-hidden="true" className="h-2 w-2 rounded-sm" style={{ background: DOWN }} /> cierre &lt; apertura
        </span>
      </div>
    </div>
  )
}
