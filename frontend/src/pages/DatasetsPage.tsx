export default function DatasetsPage() {
  const datasets = [
    { id: 1, name: 'Monero Historical Data', domain: 'Crypto', records: 1500, status: 'Ready' },
    { id: 2, name: 'Store Sales Ecuador', domain: 'Retail', records: 54000, status: 'Ready' },
  ]

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Datasets</h2>

      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
        <table className="w-full">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Nombre</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Dominio</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Registros</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Estado</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {datasets.map((dataset) => (
              <tr key={dataset.id} className="hover:bg-gray-50">
                <td className="px-6 py-4 font-medium text-gray-900">{dataset.name}</td>
                <td className="px-6 py-4">
                  <span className="px-2 py-1 text-xs font-medium bg-green-100 text-green-800 rounded">
                    {dataset.domain}
                  </span>
                </td>
                <td className="px-6 py-4 text-gray-600">{dataset.records.toLocaleString()}</td>
                <td className="px-6 py-4">
                  <span className="px-2 py-1 text-xs font-medium bg-blue-100 text-blue-800 rounded">
                    {dataset.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
