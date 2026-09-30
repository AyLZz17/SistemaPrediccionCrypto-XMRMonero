import type { User } from './session'

export type ExperimentStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export const EXPERIMENT_STATUSES: ExperimentStatus[] = [
  'PENDING',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
]

export interface Dataset {
  id: string
  name: string
  symbol: string
  interval: string
  rows: number
  checksum?: string
  from?: string
  to?: string
  createdAt?: string
}

export interface Experiment {
  id: string
  name: string
  description?: string
  status: ExperimentStatus
  task: 'REGRESSION' | 'DIRECTION' | 'BOTH'
  modelFamily?: 'LSTM' | 'GRU' | 'BASELINE'
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  datasetId?: string
}

export interface ExperimentRun {
  id: string
  experimentId: string
  runId: string
  status: ExperimentStatus
  seed?: number
  startedAt?: string
  finishedAt?: string
  mlflowRunId?: string
  metrics?: MetricSet
  message?: string
}

export interface MetricSet {
  mae?: number
  rmse?: number
  mape?: number
  /** Proportion of correct direction calls, 0..1. */
  directionAccuracy?: number
  meanStdDev?: number
  validation?: MetricSet
  test?: MetricSet
}

export interface ModelVersion {
  id: string
  modelId: string
  /**
   * Version tal como la nombra el backend. Se mantiene como cadena y no como
   * numero porque la base de datos la almacena como etiqueta de version, no como
   * un entero: convertirla aqui obligaria al cliente a inventar una regla de
   * parseo que el servidor ya conoce.
   */
  version: string
  label?: string
  isChampion: boolean
  createdAt?: string
  metrics?: MetricSet
  digest?: string
}

/** Familias reales del dominio. Las tres ultimas son los baselines de R-06. */
export type ModelFamily = 'LSTM' | 'GRU' | 'MOVING_AVERAGE' | 'LINEAR_REGRESSION' | 'ARIMA'

export interface ModelDescriptor {
  id: string
  name: string
  family: ModelFamily
  task: 'REGRESSION' | 'DIRECTION' | 'BOTH'
  description?: string
  championVersionId?: string | null
  versions?: ModelVersion[]
}

export type PredictionStatus = 'PENDING' | 'READY' | 'FAILED'

export interface Prediction {
  id: string
  modelId: string
  modelName?: string
  targetDate: string
  predictedClose?: number
  predictedDirection?: 'UP' | 'DOWN' | 'FLAT'
  actualClose?: number | null
  status: PredictionStatus
  confidence?: number
  createdAt?: string
  /** Evaluation-only notice: this is a forecast, not advice. */
  disclaimer?: string
}

export type JobType = 'INGEST' | 'TRAIN' | 'PREDICT' | 'BACKFILL'
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface Job {
  id: string
  type: JobType
  status: JobStatus
  progress?: number
  experimentId?: string | null
  createdBy?: string
  createdAt?: string
  startedAt?: string | null
  finishedAt?: string | null
  message?: string
}

export interface AuditEntry {
  id: string
  timestamp: string
  actorId?: string
  actorEmail?: string
  action: string
  resourceType?: string
  resourceId?: string
  /** `DENIED` lo emite el backend cuando una peticion se rechaza por permisos. */
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE'
  requestId?: string
  detail?: string
}

export interface AppNotification {
  id: string
  title: string
  body?: string
  severity: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'
  createdAt: string
  read: boolean
  link?: string
}

/** Admin list view of an account; same shape as `User` plus admin fields. */
export interface AdminUser extends User {
  enabled?: boolean
  mfaEnabled?: boolean
  createdAt?: string
}
