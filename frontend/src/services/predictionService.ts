import api from './api'
import type { Prediction, PredictionRequest } from '../types/prediction'

export const predictionService = {
  async getPredictions(): Promise<{ items: Prediction[]; total: number }> {
    const response = await api.get('/predictions')
    return response.data
  },

  async getPrediction(id: number): Promise<Prediction> {
    const response = await api.get<Prediction>(`/predictions/${id}`)
    return response.data
  },

  async createPrediction(data: PredictionRequest): Promise<Prediction> {
    const response = await api.post<Prediction>('/predictions', data)
    return response.data
  },
}
