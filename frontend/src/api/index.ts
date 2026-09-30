/** Predictions, metrics, jobs, notifications and admin endpoints. */

import { apiRequest } from './client'
import { DEFAULT_PAGE_SIZE, isPage, type AppNotification, type AuditEntry, type AdminUser, type Job, type MetricSet, type Page, type Prediction } from '../types'
import type { Role } from '../types'

const BASE = '/api/v1'

function ensurePage<T>(value: unknown, size: number): Page<T> {
  if (isPage<T>(value)) return value
  return { items: [], page: 0, size, total: 0, totalPages: 0 }
}

/* ------------------------------------------------------------ predictions */

export interface CreatePredictionRequest {
  modelId: string
  targetDate: string
}

export async function createPrediction(payload: CreatePredictionRequest): Promise<Prediction> {
  const { data } = await apiRequest<Prediction>(`${BASE}/predictions`, { method: 'POST', body: payload })
  return data
}

export async function fetchPredictions(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<Prediction>> {
  const { data } = await apiRequest<unknown>(`${BASE}/predictions`, { query: { page, size } })
  return ensurePage<Prediction>(data, size)
}

export async function fetchPrediction(id: string): Promise<Prediction> {
  const { data } = await apiRequest<Prediction>(`${BASE}/predictions/${id}`)
  return data
}

/* ---------------------------------------------------------------- metrics */

export interface ComparisonRow {
  label: string
  modelId?: string
  modelName?: string
  family?: string
  isChampion?: boolean
  metrics: MetricSet
}

export async function fetchMetricComparison(experimentId: string): Promise<ComparisonRow[]> {
  const { data } = await apiRequest<unknown>(`${BASE}/metrics/compare`, { query: { experimentId } })
  if (isPage<ComparisonRow>(data)) return data.items
  return Array.isArray(data) ? (data as ComparisonRow[]) : []
}

export async function fetchExperimentMetrics(experimentId: string): Promise<MetricSet> {
  const { data } = await apiRequest<MetricSet>(`${BASE}/metrics/experiments/${experimentId}`)
  return data
}

/* ------------------------------------------------------------------- jobs */

export async function fetchJobs(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<Job>> {
  const { data } = await apiRequest<unknown>(`${BASE}/jobs`, { query: { page, size } })
  return ensurePage<Job>(data, size)
}

export async function fetchJob(id: string): Promise<Job> {
  const { data } = await apiRequest<Job>(`${BASE}/jobs/${id}`)
  return data
}

export async function cancelJob(id: string): Promise<Job> {
  const { data } = await apiRequest<Job>(`${BASE}/jobs/${id}/cancel`, { method: 'POST' })
  return data
}

/* ---------------------------------------------------------- notifications */

export async function fetchNotifications(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<AppNotification>> {
  const { data } = await apiRequest<unknown>(`${BASE}/notifications`, { query: { page, size } })
  return ensurePage<AppNotification>(data, size)
}

export async function markNotificationRead(id: string): Promise<void> {
  await apiRequest<null>(`${BASE}/notifications/${id}/read`, { method: 'POST' })
}

/* ------------------------------------------------------------------ admin */

export async function fetchAudit(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<AuditEntry>> {
  const { data } = await apiRequest<unknown>(`${BASE}/audit`, { query: { page, size } })
  return ensurePage<AuditEntry>(data, size)
}

export async function fetchUsers(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<AdminUser>> {
  const { data } = await apiRequest<unknown>(`${BASE}/users`, { query: { page, size } })
  return ensurePage<AdminUser>(data, size)
}

export async function updateUserRole(id: string, role: Role): Promise<AdminUser> {
  const { data } = await apiRequest<AdminUser>(`${BASE}/users/${id}/role`, { method: 'PATCH', body: { role } })
  return data
}
