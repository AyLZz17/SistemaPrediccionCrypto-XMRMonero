import { useState } from 'react'
import Input from '../common/Input'
import Button from '../common/Button'

interface ModelConfigFormProps {
  onSubmit: (data: any) => void
  loading?: boolean
}

export default function ModelConfigForm({ onSubmit, loading }: ModelConfigFormProps) {
  const [modelType, setModelType] = useState('lstm')
  const [units, setUnits] = useState('100,100')
  const [dropout, setDropout] = useState(0.2)
  const [batchSize, setBatchSize] = useState(32)
  const [epochs, setEpochs] = useState(20)
  const [windowSize, setWindowSize] = useState(30)
  const [horizon, setHorizon] = useState(1)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({
      model_type: modelType,
      hyperparameters: {
        units: units.split(',').map(Number),
        dropout,
        batch_size: batchSize,
        epochs,
        window_size: windowSize,
        horizon,
      },
    })
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Tipo de Modelo
        </label>
        <select
          value={modelType}
          onChange={(e) => setModelType(e.target.value)}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
        >
          <option value="lstm">LSTM</option>
          <option value="gru">GRU</option>
          <option value="arima">ARIMA</option>
        </select>
      </div>
      <Input
        label="Unidades (separadas por coma)"
        value={units}
        onChange={(e) => setUnits(e.target.value)}
        placeholder="100,100"
      />
      <Input
        label="Dropout"
        type="number"
        step="0.1"
        min="0"
        max="1"
        value={dropout}
        onChange={(e) => setDropout(Number(e.target.value))}
      />
      <Input
        label="Batch Size"
        type="number"
        value={batchSize}
        onChange={(e) => setBatchSize(Number(e.target.value))}
      />
      <Input
        label="Épocas"
        type="number"
        value={epochs}
        onChange={(e) => setEpochs(Number(e.target.value))}
      />
      <Input
        label="Tamaño de Ventana"
        type="number"
        value={windowSize}
        onChange={(e) => setWindowSize(Number(e.target.value))}
      />
      <Input
        label="Horizonte"
        type="number"
        value={horizon}
        onChange={(e) => setHorizon(Number(e.target.value))}
      />
      <Button type="submit" disabled={loading} className="w-full">
        {loading ? 'Entrenando...' : 'Entrenar Modelo'}
      </Button>
    </form>
  )
}
