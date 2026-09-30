import { useMemo } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { ComparisonRow } from '../../api'

const SERIES = [
  { key: 'mae', label: 'MAE (USD)', color: 'var(--xmr-accent-cyan)' },
  { key: 'rmse', label: 'RMSE (USD)', color: 'var(--xmr-accent-violet)' },
  { key: 'mape', label: 'MAPE (%)', color: 'var(--xmr-accent-amber)' },
  { key: 'directionAccuracy', label: 'Direccion (%)', color: 'var(--xmr-accent-green)' },
] as const

/**
 * Model comparison chart. MAE/RMSE/MAPE live in USD/percent units and
 * direction accuracy is a 0..1 ratio, so we normalise to percent before
 * plotting and say so in the legend.
 */
export function MetricComparisonChart({ rows }: { rows: ComparisonRow[] }) {
  const data = useMemo(
    () =>
      rows.map((row) => ({
        name: row.modelName ?? row.label,
        mae: row.metrics.mae ?? 0,
        rmse: row.metrics.rmse ?? 0,
        mape: row.metrics.mape ?? 0,
        directionAccuracy: (row.metrics.directionAccuracy ?? 0) * 100,
      })),
    [rows],
  )

  if (data.length === 0) return null

  return (
    <div className="h-80 w-full" role="img" aria-label="Comparativa de metricas por modelo">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 4, left: 4 }}>
          <CartesianGrid stroke="var(--xmr-border-subtle)" vertical={false} />
          <XAxis
            dataKey="name"
            tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
            axisLine={{ stroke: 'var(--xmr-border-default)' }}
            tickLine={false}
          />
          <YAxis
            tick={{ fill: 'var(--xmr-text-muted)', fontSize: 10, fontFamily: 'var(--xmr-font-mono)' }}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            contentStyle={{
              background: 'var(--xmr-surface-2)',
              border: '1px solid var(--xmr-border-default)',
              borderRadius: 'var(--xmr-radius-md)',
              fontFamily: 'var(--xmr-font-mono)',
              fontSize: 12,
              color: 'var(--xmr-text-primary)',
            }}
          />
          <Legend
            wrapperStyle={{ fontSize: 11, fontFamily: 'var(--xmr-font-mono)', color: 'var(--xmr-text-secondary)' }}
          />
          {SERIES.map((series) => (
            <Bar
              key={series.key}
              dataKey={series.key}
              name={series.label}
              fill={series.color}
              radius={[3, 3, 0, 0]}
              isAnimationActive={false}
            />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
