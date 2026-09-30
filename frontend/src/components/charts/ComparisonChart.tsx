import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'

interface ComparisonChartProps {
  data: Array<{
    model: string
    mae: number
    rmse: number
    mape: number
  }>
  title: string
  height?: number
}

export default function ComparisonChart({
  data,
  title,
  height = 300,
}: ComparisonChartProps) {
  return (
    <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
      <h3 className="text-lg font-semibold text-gray-800 mb-4">{title}</h3>
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="model" />
          <YAxis />
          <Tooltip />
          <Legend />
          <Bar dataKey="mae" fill="#4f46e5" name="MAE" />
          <Bar dataKey="rmse" fill="#10b981" name="RMSE" />
          <Bar dataKey="mape" fill="#f59e0b" name="MAPE" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
