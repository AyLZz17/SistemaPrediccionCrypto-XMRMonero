export interface Hyperparameters {
  units: number[]
  dropout: number
  batch_size: number
  epochs: number
  window_size: number
  horizon: number
}

export interface TrainRequest {
  dataset_id: number
  model_type: string
  hyperparameters: Hyperparameters
}

export interface TrainResponse {
  task_id: string
  status: string
  message: string
}

export interface Model {
  id: number
  name: string
  type: string
  dataset_id: number
  status: string
  metrics?: {
    mae: number
    rmse: number
    mape: number
  }
  trained_at?: string
}
