export interface PredictionItem {
  date: string
  predicted_value: number
  confidence_interval?: {
    lower: number
    upper: number
  }
}

export interface Prediction {
  id: number
  model_id: number
  dataset_id: number
  predictions: PredictionItem[]
  metrics?: {
    mae: number
    rmse: number
    mape: number
  }
  created_at: string
}

export interface PredictionRequest {
  model_id: number
  dataset_id: number
  horizon: number
  window_size: number
}
