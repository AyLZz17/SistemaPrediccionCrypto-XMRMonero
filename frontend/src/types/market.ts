export const DEFAULT_SYMBOL = 'XMR-USD'

export interface Candle {
  id?: string
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  quoteVolume?: number
  changePercent?: number
}

export interface Quote {
  symbol: string
  price: number
  open?: number
  high?: number
  low?: number
  previousClose?: number
  change?: number
  changePercent?: number
  volume?: number
  marketTime?: string
  updatedAt?: string
  source?: string
}

export interface CandleQuery extends CandleQueryBase {
  symbol?: string
}

export interface CandleQueryBase {
  from?: string
  to?: string
}
