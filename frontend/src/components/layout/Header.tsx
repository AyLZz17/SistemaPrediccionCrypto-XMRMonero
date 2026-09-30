import { useAuthStore } from '../../store/authStore'

export default function Header() {
  const { user, logout } = useAuthStore()

  return (
    <header className="bg-white shadow-sm border-b border-gray-200">
      <div className="px-6 py-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-indigo-600">XMR-Forecast</h1>
        <div className="flex items-center gap-4">
          <span className="text-sm text-gray-600">{user?.email}</span>
          <button
            onClick={logout}
            className="px-4 py-2 text-sm bg-red-500 text-white rounded-lg hover:bg-red-600 transition"
          >
            Cerrar Sesión
          </button>
        </div>
      </div>
    </header>
  )
}
