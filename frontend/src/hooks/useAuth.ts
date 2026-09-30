import { useAuthStore } from '../store/authStore'

export const useAuth = () => {
  const { user, isAuthenticated, login, logout } = useAuthStore()

  return {
    user,
    isAuthenticated,
    isAdmin: user?.role === 'admin',
    isAnalyst: user?.role === 'analyst' || user?.role === 'admin',
    login,
    logout,
  }
}
