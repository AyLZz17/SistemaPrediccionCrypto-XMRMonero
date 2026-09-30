import { useState } from 'react'
import Input from '../common/Input'
import Button from '../common/Button'

interface PredictionFormProps {
  models: { id: number; name: string }[]
  datasets: { id: number; name: string }[]
  onSubmit: (data: any) => void
  loading?: boolean
}

export default function PredictionForm({
  models,
  datasets,
  onSubmit,
  loading,
}: PredictionFormProps) {
  const [modelId, setModelId] = useState<number | ''>('')
  const [datasetId, setDatasetId] = useState<number | ''>('')
  const [horizon, setHorizon] = useState(1)
  const [windowSize, setWindowSize] = useState(30)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      model_id: modelId,
      dataset_id: datasetId,
      horizon,
      window_size: windowSize,
    })
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Modelo
        </label>
        <select
          value={modelId}
          onChange={(e) => setModelId(Number(e.target.value))}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
          required
        >
          <option value="">Seleccionar modelo...</option>
          {models.map((model) => (
            <option key={model.id} value={model.id}>
              {model.name}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Dataset
        </label>
        <select
          value={datasetId}
          onChange={(e) => setDatasetId(Number(e.target.value))}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
          required
        >
          <option value="">Seleccionar dataset...</option>
          {datasets.map((dataset) => (
            <option key={dataset.id} value={dataset.id}>
              {dataset.name}
            </option>
          ))}
        </select>
      </div>
      <Input
        label="Horizonte (días)"
        type="number"
        min="1"
        max="30"
        value={horizon}
        onChange={(e) => setHorizon(Number(e.target.value))}
      />
      <Input
        label="Tamaño de Ventana"
        type="number"
        value={windowSize}
        onChange={(e) => setWindowSize(Number(e.target.value))}
      />
      <Button
        type="submit"
        disabled={loading || !modelId || !datasetId}
        className="w-full"
      >
        {loading ? 'Generando predicción...' : 'Generar Predicción'}
      </Button>
    </form>
  )
}
