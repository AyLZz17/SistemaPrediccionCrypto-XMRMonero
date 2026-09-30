import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

const mockData = [
  { date: '2026-09-01', actual: 150.2, predicted: 148.5 },
  { date: '2026-09-02', actual: 152.8, predicted: 151.2 },
  { date: '2026-09-03', actual: 149.5, predicted: 150.8 },
  { date: '2026-09-04', actual: 155.3, predicted: 153.1 },
  { date: '2026-09-05', actual: 158.7, predicted: 156.4 },
  { date: '2026-09-06', actual: 156.2, predicted: 157.8 },
  { date: '2026-09-07', actual: 160.5, predicted: 158.9 },
]

export default function DashboardPage() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Dashboard</h2>

      {/* KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
          <p className="text-sm text-gray-500">Datasets</p>
          <p className="text-3xl font-bold text-indigo-600">5</p>
        </div>
        <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
          <p className="text-sm text-gray-500">Modelos Entrenados</p>
          <p className="text-3xl font-bold text-green-600">12</p>
        </div>
        <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
          <p className="text-sm text-gray-500">Predicciones</p>
          <p className="text-3xl font-bold text-purple-600">156</p>
        </div>
        <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
          <p className="text-sm text-gray-500">Mejor Modelo</p>
          <p className="text-3xl font-bold text-orange-600">LSTM</p>
        </div>
      </div>

      {/* Gráfico */}
      <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
        <h3 className="text-lg font-semibold text-gray-800 mb-4">
          Precio de Monero (XMR) - Real vs Predicho
        </h3>
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={mockData}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="date" />
            <YAxis />
            <Tooltip />
            <Line type="monotone" dataKey="actual" stroke="#4f46e5" name="Real" strokeWidth={2} />
            <Line type="monotone" dataKey="predicted" stroke="#10b981" name="Predicho" strokeWidth={2} strokeDasharray="5 5" />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
