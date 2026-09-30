import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { useAuthStore } from '../store/authStore'
import { resetBootstrap } from '../auth/session'
import { resetRefreshCoordinator } from '../auth/refreshCoordinator'
import { resetEnvCache } from '../config/env'

/*
 * Global test setup.
 * R-18: tests never hit the network. `globalThis.fetch` is replaced by a spy in
 * every test that performs a request; anything unexpected rejects immediately.
 */

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  useAuthStore.setState({
    status: 'anonymous',
    accessToken: null,
    expiresAt: null,
    refreshToken: null,
    user: null,
    invalidatedReason: null,
  })
  resetBootstrap()
  resetRefreshCoordinator()
  resetEnvCache()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})
