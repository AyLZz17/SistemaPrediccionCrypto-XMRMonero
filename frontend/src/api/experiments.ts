/** Datasets, experiments, runs and models endpoints. */

import { apiRequest } from './client'
import { DEFAULT_PAGE_SIZE, isPage, type Dataset, type Experiment, type ExperimentRun, type ModelDescriptor, type ModelVersion, type Page } from '../types'

const DATASETS = '/api/v1/datasets'
const EXPERIMENTS = '/api/v1/experiments'
const MODELS = '/api/v1/models'

function ensurePage<T>(value: unknown, size: number): Page<T> {
  if (isPage<T>(value)) return value
  return { items: [], page: 0, size, total: 0, totalPages: 0 }
}

export async function fetchDatasets(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<Dataset>> {
  const { data } = await apiRequest<unknown>(DATASETS, { query: { page, size } })
  return ensurePage<Dataset>(data, size)
}

export async function createDataset(payload: Partial<Dataset>): Promise<Dataset> {
  const { data } = await apiRequest<Dataset>(DATASETS, { method: 'POST', body: payload })
  return data
}

export async function fetchExperiments(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<Experiment>> {
  const { data } = await apiRequest<unknown>(EXPERIMENTS, { query: { page, size } })
  return ensurePage<Experiment>(data, size)
}

export async function createExperiment(payload: Partial<Experiment>): Promise<Experiment> {
  const { data } = await apiRequest<Experiment>(EXPERIMENTS, { method: 'POST', body: payload })
  return data
}

export async function startRun(experimentId: string, payload: Record<string, unknown> = {}): Promise<ExperimentRun> {
  const { data } = await apiRequest<ExperimentRun>(`${EXPERIMENTS}/${experimentId}/runs`, {
    method: 'POST',
    body: payload,
  })
  return data
}

export async function fetchRuns(experimentId: string, page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<ExperimentRun>> {
  const { data } = await apiRequest<unknown>(`${EXPERIMENTS}/${experimentId}/runs`, { query: { page, size } })
  return ensurePage<ExperimentRun>(data, size)
}

export async function fetchModels(page = 0, size = DEFAULT_PAGE_SIZE): Promise<Page<ModelDescriptor>> {
  const { data } = await apiRequest<unknown>(MODELS, { query: { page, size } })
  return ensurePage<ModelDescriptor>(data, size)
}

export async function fetchModelVersions(modelId: string): Promise<ModelVersion[]> {
  const { data } = await apiRequest<unknown>(`${MODELS}/${modelId}/versions`)
  if (isPage<ModelVersion>(data)) return data.items
  return Array.isArray(data) ? (data as ModelVersion[]) : []
}

/** ADMIN only (R-27). Backend re-checks the role regardless of the UI. */
export async function promoteModelVersion(modelId: string, versionId: string): Promise<ModelVersion> {
  const { data } = await apiRequest<ModelVersion>(`${MODELS}/${modelId}/promote`, {
    method: 'POST',
    body: { versionId },
  })
  return data
}
