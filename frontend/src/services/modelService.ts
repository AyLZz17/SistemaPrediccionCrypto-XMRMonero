import api from './api'
import type { Model, TrainRequest, TrainResponse } from '../types/model'

export const modelService = {
  async getModels(): Promise<{ items: Model[]; total: number }> {
    const response = await api.get('/models')
    return response.data
  },

  async getModel(id: number): Promise<Model> {
    const response = await api.get<Model>(`/models/${id}`)
    return response.data
  },

  async trainModel(data: TrainRequest): Promise<TrainResponse> {
    const response = await api.post<TrainResponse>('/models/train', data)
    return response.data
  },
}
