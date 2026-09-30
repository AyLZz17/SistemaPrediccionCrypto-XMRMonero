import api from './api'
import type { LoginRequest, TokenResponse } from '../types/auth'

export const authService = {
  async login(data: LoginRequest): Promise<TokenResponse> {
    const response = await api.post<TokenResponse>('/auth/login', data)
    return response.data
  },

  async getMe() {
    const response = await api.get('/auth/me')
    return response.data
  },
}
