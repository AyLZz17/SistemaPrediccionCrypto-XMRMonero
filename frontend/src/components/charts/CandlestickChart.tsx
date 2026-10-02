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
    <div>
      <div className="h-80 w-full min-w-0" role="img" aria-label="Gráfico de velas japonesas de XMR-USD">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 8, right: 8, bottom: 4, left: 4 }} barCategoryGap="36%">
            <CartesianGrid stroke="var(--xmr-border-hairline)" vertical={false} />
            <XAxis
              dataKey="date"
              tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
              axisLine={{ stroke: 'var(--xmr-border-strong)' }}
              tickLine={false}
              minTickGap={24}
            />
            <YAxis
              tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
              axisLine={false}
              tickLine={false}
              width={56}
              domain={['auto', 'auto']}
            />
            <Tooltip
              cursor={{ fill: 'var(--xmr-surface-2)' }}
              contentStyle={{
                background: 'var(--xmr-surface-modal)',
                border: '1px solid var(--xmr-border-strong)',
                borderRadius: 'var(--xmr-radius-sm)',
                fontFamily: 'var(--xmr-font-mono)',
                fontSize: 12,
                color: 'var(--xmr-text-primary)',
              }}
            />
            <Bar dataKey="range" fill="var(--xmr-border-default)" radius={0} isAnimationActive={false} />
            <Bar dataKey="body" radius={0} isAnimationActive={false}>
              {data.map((entry) => (
                <Cell key={`${entry.date}-${entry.fill}`} fill={entry.fill} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ul className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-1 border-t border-hairline-subtle pt-2 font-mono text-[11px] text-ink-muted">
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="inline-block h-2 w-2 bg-accent-green" />
          {'cierre \u2265 apertura · sube'}
        </li>
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="inline-block h-2 w-2 bg-accent-red" />
          {'cierre < apertura · baja'}
        </li>
        <li className="ml-auto tabular-nums">Últimas {data.length} velas diarias</li>
      </ul>
    </div>
  )
}
