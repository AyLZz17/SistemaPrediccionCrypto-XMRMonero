import api from './api'
import type { Dataset, DatasetListResponse } from '../types/dataset'

export const datasetService = {
  async getDatasets(): Promise<DatasetListResponse> {
    const response = await api.get<DatasetListResponse>('/datasets')
    return response.data
  },

  async getDataset(id: number): Promise<Dataset> {
    const response = await api.get<Dataset>(`/datasets/${id}`)
    return response.data
  },

  async createDataset(data: Partial<Dataset>): Promise<Dataset> {
    const response = await api.post<Dataset>('/datasets', data)
    return response.data
  },
}
