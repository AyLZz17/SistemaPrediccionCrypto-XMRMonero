import { render, type RenderResult } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { useAuthStore } from '../store/authStore'
import { resetRefreshCoordinator } from '../auth/refreshCoordinator'
import { resetBootstrap } from '../auth/session'
import type { Role, User } from '../types'

export const TEST_ACCESS_TOKEN = 'test-access-token'
export const TEST_REFRESH_TOKEN = 'test-refresh-token'

export function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: 'user-1',
    email: 'analista@ejemplo.com',
    fullName: 'Ana Lector',
    role: 'ANALYST' as Role,
    ...overrides,
  }
}

export function makeTokenResponse(overrides: Partial<{ accessToken: string; refreshToken: string; expiresIn: number; user: User }> = {}) {
  return {
    accessToken: overrides.accessToken ?? TEST_ACCESS_TOKEN,
    refreshToken: overrides.refreshToken ?? TEST_REFRESH_TOKEN,
    tokenType: 'Bearer',
    expiresIn: overrides.expiresIn ?? 900,
    user: overrides.user ?? makeUser(),
  }
}

/** Puts the store straight into an authenticated state (skips bootstrap). */
export function authenticate(role: Role = 'ANALYST', user: Partial<User> = {}): User {
  const resolved = makeUser({ role, ...user })
  sessionStorage.setItem('xmr-forecast.refresh-token', TEST_REFRESH_TOKEN)
  useAuthStore.setState({
    status: 'authenticated',
    accessToken: TEST_ACCESS_TOKEN,
    expiresAt: Date.now() + 900_000,
    refreshToken: TEST_REFRESH_TOKEN,
    user: resolved,
    invalidatedReason: null,
  })
  return resolved
}

export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        // Components that opt into `retry: 1` must not add a real 1s delay in tests.
        retryDelay: () => 0,
        staleTime: 0,
        gcTime: 60_000,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
      },
      mutations: { retry: false },
    },
  })
}

export interface RenderAppResult extends RenderResult {
  queryClient: QueryClient
}

/**
 * Renders the real `<App />` inside a MemoryRouter so routing, guards and both
 * layouts are exercised exactly as in production.
 */
export function renderApp(initialPath = '/'): RenderAppResult {
  const queryClient = makeQueryClient()
  resetBootstrap()
  resetRefreshCoordinator()

  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="*" element={<AppRoutesUnderTest />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}

/** Imported lazily to keep this helper tree-shakeable and cycle-free. */
import AppRoutes from '../AppRoutes'

function AppRoutesUnderTest(): ReactElement {
  return <AppRoutes />
}

export interface RenderAtPathResult extends RenderResult {
  queryClient: QueryClient
}

/** Renders a single element inside providers + MemoryRouter. */
export function renderAt(initialPath: string, element: ReactElement): RenderAtPathResult {
  const queryClient = makeQueryClient()
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>{element}</MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}
