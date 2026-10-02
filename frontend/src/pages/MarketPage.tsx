import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchCandles, fetchLatestQuote } from '../api/market'
import { DEFAULT_SYMBOL, type Candle } from '../types'
import { toApiError } from '../api/errors'
import { Button, EmptyState, ErrorState, MetricCard, Section, SelectField, StatusDot, TextField } from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { CandlestickChart } from '../components/charts/CandlestickChart'
import { formatNumber, formatUsd, toIsoDate } from '../utils/format'

function isoDaysAgo(days: number): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() - days)
  return toIsoDate(date)
}

/**
 * Terminal de datos de mercado: la cotización manda (cinta de grandes
 * numerales tabulares), el gráfico de velas domina la vista en consola
 * empotrada y la tabla OHLC cierra la página con la paginación del endpoint.
 */
export default function MarketPage() {
  const [symbol, setSymbol] = useState(DEFAULT_SYMBOL)
  const [from, setFrom] = useState(isoDaysAgo(90))
  const [to, setTo] = useState(toIsoDate(new Date()))
  const [page, setPage] = useState(0)
  const [applied, setApplied] = useState({ symbol, from, to })

  const quote = useQuery({
    queryKey: ['market', 'latest', applied.symbol],
    queryFn: () => fetchLatestQuote(applied.symbol),
    retry: 1,
    staleTime: 30_000,
  })

  const candles = useQuery({
    queryKey: ['market', 'candles', applied.symbol, applied.from, applied.to, page],
    queryFn: () => fetchCandles({ ...applied, page, size: 30 }),
    retry: 1,
  })

  const rows: Candle[] = useMemo(() => candles.data?.items ?? [], [candles.data])

  const columns: Array<Column<Candle>> = [
    { key: 'date', header: 'Fecha', render: (row) => <span className="font-mono tabular-nums">{row.date}</span> },
    { key: 'open', header: 'Apertura', numeric: true, render: (row) => formatUsd(row.open) },
    { key: 'high', header: 'Máximo', numeric: true, render: (row) => formatUsd(row.high) },
    { key: 'low', header: 'Mínimo', numeric: true, render: (row) => formatUsd(row.low) },
    { key: 'close', header: 'Cierre', numeric: true, render: (row) => <strong className="font-semibold text-ink">{formatUsd(row.close)}</strong> },
    { key: 'volume', header: 'Volumen', numeric: true, render: (row) => formatNumber(row.volume, 0) },
  ]

  const quoteUp = (quote.data?.changePercent ?? 0) >= 0

  return (
    <div className="space-y-9">
      <div className="border-b-2 border-hairline-strong pb-5">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">Terminal · datos de mercado</p>
            <h1 className="mt-1.5 font-mono text-2xl font-semibold tracking-tight text-ink sm:text-3xl">Mercado XMR-USD</h1>
            <p className="mt-1.5 max-w-3xl text-[13px] text-ink-secondary">
              Velas diarias servidas por el backend desde snapshots con checksum. El frontend nunca accede a la
              base de datos ni a fuentes externas.
            </p>
          </div>
          <StatusDot
            tone={candles.isError ? 'danger' : candles.isPending ? 'warning' : 'success'}
            label={candles.isError ? 'Sin conexión' : candles.isPending ? 'Consultando' : 'Datos actualizados'}
            pulse={candles.isFetching}
          />
        </div>

        <div className="mt-4 grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-4">
          <MetricCard
            label={`Precio · ${applied.symbol}`}
            value={quote.isPending ? '—' : formatUsd(quote.data?.price)}
            tone="idle"
            hint="Último dato ingerido"
          />
          <MetricCard
            label="Variación diaria"
            value={typeof quote.data?.changePercent === 'number' ? `${quoteUp ? '+' : ''}${quote.data.changePercent.toFixed(2)}` : '—'}
            unit="%"
            tone={quoteUp ? 'success' : 'danger'}
            trend={quoteUp ? 'up' : 'down'}
            hint="Frente al cierre anterior"
          />
          <MetricCard
            label="Rango de la sesión"
            value={quote.isPending ? '—' : `${formatUsd(quote.data?.low)} – ${formatUsd(quote.data?.high)}`}
            tone="idle"
            hint="Mínimo – máximo del día"
          />
          <MetricCard
            label="Apertura · volumen"
            value={quote.isPending ? '—' : formatUsd(quote.data?.open)}
            tone="idle"
            hint={`Volumen ${quote.data?.volume !== undefined ? formatNumber(quote.data.volume, 0) : '—'}`}
          />
        </div>
      </div>

      {/* -------------------------------------------------------- filtros */}
      <form
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setApplied({ symbol: symbol.trim() || DEFAULT_SYMBOL, from, to })
        }}
        aria-label="Rango temporal de la terminal"
      >
        <div className="grid gap-3 border-y border-hairline-subtle py-3 md:grid-cols-[1fr_1fr_1fr_auto] md:items-end">
          <SelectField
            label="Símbolo"
            name="symbol"
            value={symbol}
            onChange={(event) => setSymbol(event.target.value)}
          >
            <option value="XMR-USD">XMR-USD</option>
            <option value="XMR-EUR">XMR-EUR</option>
            <option value="XMR-BTC">XMR-BTC</option>
          </SelectField>
          <TextField
            label="Desde"
            name="from"
            type="date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
          <TextField
            label="Hasta"
            name="to"
            type="date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
          <Button type="submit" variant="secondary">
            Aplicar rango
          </Button>
        </div>
      </form>

      {/* -------------------------------------------------------- gráfico */}
      <Section
        index="01"
        eyebrow="Precio"
        title="Evolución del precio"
        description={`${applied.symbol} · ${applied.from} a ${applied.to}`}
      >
        {candles.isPending ? (
          <div className="skeleton-bar h-80 w-full min-w-0" aria-label="Cargando gráfico" role="status" />
        ) : candles.isError ? (
          <ErrorState
            message={toApiError(candles.error).friendlyMessage}
            requestId={toApiError(candles.error).requestId}
            onRetry={() => void candles.refetch()}
          />
        ) : rows.length === 0 ? (
          <EmptyState
            title="Sin velas en el rango seleccionado"
            description="Amplía el intervalo de fechas o verifica que el símbolo tenga datos ingeridos."
          />
        ) : (
          <div className="console-well p-3 sm:p-4">
            <CandlestickChart candles={rows} />
          </div>
        )}
      </Section>

      {/* ---------------------------------------------------------- tabla */}
      <Section
        index="02"
        eyebrow="OHLC"
        title="Tabla de velas"
        description="Página actual del endpoint paginado"
      >
        {candles.isPending ? (
          <div className="skeleton-bar h-64 w-full min-w-0" role="status" aria-label="Cargando tabla" />
        ) : candles.isError ? (
          <ErrorState
            message={toApiError(candles.error).friendlyMessage}
            requestId={toApiError(candles.error).requestId}
            onRetry={() => void candles.refetch()}
          />
        ) : (
          <>
            <DataTable
              caption={`Velas de ${applied.symbol} entre ${applied.from} y ${applied.to}`}
              columns={columns}
              rows={rows}
              rowKey={(row, index) => `${row.date}-${index}`}
              dense
            />
            <div className="mt-1">
              <Pagination
                page={candles.data?.page ?? page}
                totalPages={candles.data?.totalPages ?? 0}
                total={candles.data?.total ?? 0}
                size={candles.data?.size ?? 30}
                onPageChange={setPage}
                disabled={candles.isFetching}
              />
            </div>
          </>
        )}
      </Section>
    </div>
  )
}
