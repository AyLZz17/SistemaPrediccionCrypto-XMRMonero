import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from './AppRoutes'
import { ErrorBoundary } from './components/common/ErrorBoundary'
import { ApiError } from './api/errors'
import { getEnv } from './config/env'

/**
 * Reading the env here means a misconfigured deployment fails fast, before any
 * request is attempted, with an actionable message (R-33, R-14).
 */
getEnv()

/** Never blindly retry a 4xx: the response will not change on its own. */
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status !== null && error.status < 500) return false
  return failureCount < 1
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        staleTime: 30_000,
        retry: shouldRetry,
      },
      mutations: {
        retry: 0,
      },
    },
  })
}

const queryClient = createQueryClient()

export function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  )
}

export default App
