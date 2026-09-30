export default function ModelsPage() {
  const models = [
    { id: 1, name: 'LSTM Monero v1', type: 'LSTM', mae: 12.5, rmse: 18.3, mape: 3.2 },
    { id: 2, name: 'GRU Monero v1', type: 'GRU', mae: 13.1, rmse: 19.0, mape: 3.5 },
    { id: 3, name: 'ARIMA Monero v1', type: 'ARIMA', mae: 15.8, rmse: 22.4, mape: 4.1 },
  ]

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Modelos</h2>

      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
        <table className="w-full">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Modelo</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Tipo</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">MAE</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">RMSE</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">MAPE</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {models.map((model) => (
              <tr key={model.id} className="hover:bg-gray-50">
                <td className="px-6 py-4 font-medium text-gray-900">{model.name}</td>
                <td className="px-6 py-4">
                  <span className="px-2 py-1 text-xs font-medium bg-indigo-100 text-indigo-800 rounded">
                    {model.type}
                  </span>
                </td>
                <td className="px-6 py-4 text-gray-600">{model.mae}</td>
                <td className="px-6 py-4 text-gray-600">{model.rmse}</td>
                <td className="px-6 py-4 text-gray-600">{model.mape}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
