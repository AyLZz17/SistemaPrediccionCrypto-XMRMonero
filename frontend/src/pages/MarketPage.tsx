import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchCandles } from '../api/market'
import { DEFAULT_SYMBOL, type Candle } from '../types'
import { toApiError } from '../api/errors'
import { Button, EmptyState, ErrorState, Panel, PanelHeader, SelectField, TextField } from '../components/ui'
import { DataTable, Pagination, type Column } from '../components/ui'
import { CandlestickChart } from '../components/charts/CandlestickChart'
import { formatNumber, formatUsd, toIsoDate } from '../utils/format'

function isoDaysAgo(days: number): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() - days)
  return toIsoDate(date)
}

export default function MarketPage() {
  const [symbol, setSymbol] = useState(DEFAULT_SYMBOL)
  const [from, setFrom] = useState(isoDaysAgo(90))
  const [to, setTo] = useState(toIsoDate(new Date()))
  const [page, setPage] = useState(0)
  const [applied, setApplied] = useState({ symbol, from, to })

  const candles = useQuery({
    queryKey: ['market', 'candles', applied.symbol, applied.from, applied.to, page],
    queryFn: () => fetchCandles({ ...applied, page, size: 30 }),
    retry: 1,
  })

  const rows: Candle[] = useMemo(() => candles.data?.items ?? [], [candles.data])

  const columns: Array<Column<Candle>> = [
    { key: 'date', header: 'Fecha', render: (row) => <span className="font-mono">{row.date}</span> },
    { key: 'open', header: 'Apertura', numeric: true, render: (row) => formatUsd(row.open) },
    { key: 'high', header: 'Máximo', numeric: true, render: (row) => formatUsd(row.high) },
    { key: 'low', header: 'Mínimo', numeric: true, render: (row) => formatUsd(row.low) },
    { key: 'close', header: 'Cierre', numeric: true, render: (row) => <strong className="text-ink">{formatUsd(row.close)}</strong> },
    { key: 'volume', header: 'Volumen', numeric: true, render: (row) => formatNumber(row.volume, 0) },
  ]

  return (
    <div className="space-y-6">
      <div>
        <p className="label-caps">Datos de mercado</p>
        <h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl tracking-tight">Mercado XMR-USD</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Velas diarias servidas por el backend desde snapshots con checksum. El frontend nunca accede a la
          base de datos ni a fuentes externas.
        </p>
      </div>

      <Panel tone="raised">
        <form
          onSubmit={(event) => {
            event.preventDefault()
            setPage(0)
            setApplied({ symbol: symbol.trim() || DEFAULT_SYMBOL, from, to })
          }}
          className="grid gap-4 md:grid-cols-4 md:items-end"
        >
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
          <Button type="submit" className="md:mb-0.5">
            Aplicar filtros
          </Button>
        </form>
      </Panel>

      <Panel tone="raised">
        <PanelHeader
          title="Evolución del precio"
          subtitle={`${applied.symbol} · ${applied.from} a ${applied.to}`}
        />
        {candles.isPending ? (
          <div className="skeleton-bar h-72 w-full min-w-0" aria-label="Cargando gráfico" role="status" />
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
          <CandlestickChart candles={rows} />
        )}
      </Panel>

      <Panel tone="raised">
        <PanelHeader title="Tabla de velas" subtitle="Página actual del endpoint paginado" />
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
            <div className="mt-4">
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
      </Panel>
    </div>
  )
}