import { NavLink } from 'react-router-dom'

const navItems = [
  { path: '/', label: 'Dashboard', icon: '📊' },
  { path: '/predictions', label: 'Predicciones', icon: '🔮' },
  { path: '/models', label: 'Modelos', icon: '🤖' },
  { path: '/datasets', label: 'Datasets', icon: '📁' },
  { path: '/http-codes', label: 'HTTP Codes', icon: '🔌' },
]

export default function Sidebar() {
  return (
    <aside className="w-64 bg-white shadow-sm border-r border-gray-200 min-h-[calc(100vh-73px)]">
      <nav className="p-4 space-y-2">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-4 py-3 rounded-lg transition ${
                isActive
                  ? 'bg-indigo-50 text-indigo-600 font-medium'
                  : 'text-gray-600 hover:bg-gray-50'
              }`
            }
          >
            <span>{item.icon}</span>
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}
