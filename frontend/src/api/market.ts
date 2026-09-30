/** Market data endpoints: `/api/v1/market/*`. */

import { apiRequest } from './client'
import { DEFAULT_PAGE_SIZE, DEFAULT_SYMBOL, isPage, type Candle, type Page, type Quote } from '../types'

const BASE = '/api/v1/market'

function ensurePage<T>(value: unknown, size: number): Page<T> {
  if (isPage<T>(value)) return value
  return { items: [], page: 0, size, total: 0, totalPages: 0 }
}

export interface CandleQuery {
  symbol?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export async function fetchCandles(query: CandleQuery = {}): Promise<Page<Candle>> {
  const size = query.size ?? DEFAULT_PAGE_SIZE
  const { data } = await apiRequest<unknown>(`${BASE}/candles`, {
    query: {
      symbol: query.symbol ?? DEFAULT_SYMBOL,
      from: query.from,
      to: query.to,
      page: query.page ?? 0,
      size,
    },
  })
  return ensurePage<Candle>(data, size)
}

export async function fetchLatestQuote(symbol: string = DEFAULT_SYMBOL): Promise<Quote> {
  const { data } = await apiRequest<Quote>(`${BASE}/latest`, { query: { symbol } })
  return data
}
