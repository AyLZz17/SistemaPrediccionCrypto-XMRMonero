/**
 * Anonymous dashboard contract (`/api/v1/public/*`).
 *
 * Everything here is safe to render without a session: aggregates only, no user,
 * prediction, experiment-run or internal identifier fields (R-26). The backend
 * is the one that decides what is public; this file must not grow fields that
 * the backend does not send, because `HttpContractTest` compares both sides.
 */

import type { MetricSet } from './domain'

/** Global model status: identity of the family plus champion presence. */
export interface PublicModelStatus {
  name: string
  family: string
  task: string
  hasChampion: boolean
}

/** One row of the public comparison. No runId, no experiment identifiers. */
export interface PublicComparisonRow {
  label: string
  family: string
  isChampion: boolean
  metrics: MetricSet
}

/**
 * Aggregated metrics of the latest completed experiment. When no run exists
 * yet the backend answers `available: false` instead of inventing numbers
 * (R-21), so every field is nullable on purpose.
 */
export interface PublicMetrics {
  experimentCode: string | null
  available: boolean
  best: MetricSet | null
  validation: MetricSet | null
  test: MetricSet | null
}

/** Freshness of the data and general state of the system. */
export interface PublicStatus {
  generatedAt: string
  dataUpdatedAt: string | null
  dataPoints: number
  models: number
  champions: number
  experimentCode: string | null
  experimentStatus: string | null
  experimentUpdatedAt: string | null
  legalVersion: string
}
