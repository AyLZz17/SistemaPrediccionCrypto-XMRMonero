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

/**
 * jsdom does not implement `ResizeObserver`, which Recharts' `ResponsiveContainer`
 * instantiates on mount: without a constructor the chart throws, React unmounts
 * the whole route and the page appears to be blank (T-043). The stub does not
 * fire, so the charts keep their zero measured size in jsdom; assertions target
 * the textual/table counterpart of every graphic, which is the accessible data
 * anyway (WCAG), never the pixels.
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
if (!('ResizeObserver' in globalThis)) {
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
}

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
