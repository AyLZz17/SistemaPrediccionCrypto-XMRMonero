import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface User {
  id: number
  email: string
  role: 'viewer' | 'analyst' | 'admin'
}

interface AuthState {
  user: User | null
  token: string | null
  isAuthenticated: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      isAuthenticated: false,

      login: async (email: string, password: string) => {
        // TODO: Implementar llamada real a la API
        const mockUser: User = {
          id: 1,
          email,
          role: 'admin',
        }
        set({
          user: mockUser,
          token: 'mock-jwt-token',
          isAuthenticated: true,
        })
      },

      logout: () => {
        set({
          user: null,
          token: null,
          isAuthenticated: false,
        })
      },
    }),
    {
      name: 'auth-storage',
    }
  )
)
