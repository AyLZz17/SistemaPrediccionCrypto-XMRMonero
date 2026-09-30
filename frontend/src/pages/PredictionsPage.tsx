import { useState } from 'react'

export default function PredictionsPage() {
  const [model, setModel] = useState('')
  const [horizon, setHorizon] = useState(1)
  const [loading, setLoading] = useState(false)

  const handlePredict = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    // TODO: Implementar llamada a la API
    setTimeout(() => setLoading(false), 2000)
  }

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Predicciones</h2>

      <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
        <h3 className="text-lg font-semibold text-gray-800 mb-4">
          Configurar Predicción
        </h3>

        <form onSubmit={handlePredict} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Modelo
            </label>
            <select
              value={model}
              onChange={(e) => setModel(e.target.value)}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
            >
              <option value="">Seleccionar modelo...</option>
              <option value="lstm">LSTM Monero v1</option>
              <option value="gru">GRU Monero v1</option>
              <option value="arima">ARIMA Monero v1</option>
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Horizonte (días)
            </label>
            <input
              type="number"
              value={horizon}
              onChange={(e) => setHorizon(Number(e.target.value))}
              min={1}
              max={30}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <button
            type="submit"
            disabled={loading || !model}
            className="w-full py-3 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700 transition disabled:opacity-50"
          >
            {loading ? 'Generando predicción...' : 'Generar Predicción'}
          </button>
        </form>
      </div>
    </div>
  )
}
