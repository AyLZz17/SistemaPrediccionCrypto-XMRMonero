/**
 * Public dashboard endpoints: `/api/v1/public/*` (anonymous, read-only).
 *
 * This is the only client module callable without a session. It never sends an
 * access token on purpose (there is none to send) and it never touches a
 * private route: advanced functions live behind the session.
 */

import { apiRequest } from './client'
import {
  DEFAULT_SYMBOL,
  type Candle,
  type PublicComparisonRow,
  type PublicMetrics,
  type PublicModelStatus,
  type PublicStatus,
  type Quote,
} from '../types'

const BASE = '/api/v1/public'

/** Last close and variation for the hero card. 404 while there is no data. */
export async function fetchPublicSummary(symbol: string = DEFAULT_SYMBOL): Promise<Quote> {
  const { data } = await apiRequest<Quote>(`${BASE}/summary`, { query: { symbol } })
  return data
}

/** Historical candles for the public chart (server caps them to 365). */
export async function fetchPublicSeries(
  symbol: string = DEFAULT_SYMBOL,
  limit = 90,
): Promise<Candle[]> {
  const { data } = await apiRequest<Candle[]>(`${BASE}/series`, { query: { symbol, limit } })
  return Array.isArray(data) ? data : []
}

/** Model catalogue status: family, task and champion presence. */
export async function fetchPublicModels(): Promise<PublicModelStatus[]> {
  const { data } = await apiRequest<PublicModelStatus[]>(`${BASE}/models`)
  return Array.isArray(data) ? data : []
}

/** Aggregated metrics of the latest completed run (may declare absence). */
export async function fetchPublicMetrics(): Promise<PublicMetrics> {
  const { data } = await apiRequest<PublicMetrics>(`${BASE}/metrics`)
  return data
}

/** Model comparison on the same run and the VALIDATION split. */
export async function fetchPublicComparison(): Promise<PublicComparisonRow[]> {
  const { data } = await apiRequest<PublicComparisonRow[]>(`${BASE}/comparison`)
  return Array.isArray(data) ? data : []
}

/** Data freshness, catalogue size and legal version. */
export async function fetchPublicStatus(): Promise<PublicStatus> {
  const { data } = await apiRequest<PublicStatus>(`${BASE}/status`)
  return data
}
