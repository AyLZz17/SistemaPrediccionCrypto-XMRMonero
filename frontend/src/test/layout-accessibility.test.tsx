import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthenticatedLayout, Footer, PublicLayout, NAV_GROUPS } from '../components/layout'
import { ForbiddenPanel } from '../components/routes/ForbiddenPanel'
import { renderApp, authenticate, makeUser } from './testUtils'
import { installFetchStub, jsonResponse } from './fetchStub'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { makeQueryClient } from './testUtils'
import { useAuthStore } from '../store/authStore'

function renderWithProviders(initialPath: string, element: JSX.Element) {
  const queryClient = makeQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="*" element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Footer', () => {
  it('renders the exact literal as the contentinfo landmark', () => {
    render(<Footer />)
    const footer = screen.getByRole('contentinfo', { name: /pie de pagina y aviso legal/i })
    expect(
      footer.textContent,
    ).toContain('Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.')
  })

  it('uses a non-breaking-safe exact string: single space, © symbol, hyphens', () => {
    render(<Footer />)
    const text = screen.getByTestId('app-footer-text').textContent ?? ''
    expect(text).toBe('Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.')
    expect(text).toContain('\u00A9')
    expect(text).toContain('AyLZz17 - AyLZz Software Solutions')
    expect(text).not.toContain('  ')
  })
})

describe('PublicLayout', () => {
  it('provides banner/main/contentinfo landmarks and a skip link', () => {
    renderWithProviders(
      '/',
      <PublicLayout>
        <h1>Contenido</h1>
      </PublicLayout>,
    )
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /saltar al contenido principal/i })).toHaveAttribute('href', '#main-content')
  })

  it('shows both auth links on the landing variant', () => {
    renderWithProviders(
      '/',
      <PublicLayout>
        <p>x</p>
      </PublicLayout>,
    )
    expect(screen.getByRole('link', { name: /iniciar sesion/i })).toHaveAttribute('href', '/login')
    expect(screen.getByRole('link', { name: /crear cuenta/i })).toHaveAttribute('href', '/register')
  })

  it('hides them on the compact auth variant and offers a way back home', () => {
    renderWithProviders(
      '/login',
      <PublicLayout variant="auth">
        <p>x</p>
      </PublicLayout>,
    )
    expect(screen.queryByRole('link', { name: /^iniciar sesion$/i })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver al inicio/i })).toHaveAttribute('href', '/')
  })

  it('always renders the mandatory footer', () => {
    renderWithProviders(
      '/login',
      <PublicLayout variant="auth">
        <p>x</p>
      </PublicLayout>,
    )
    expect(screen.getByTestId('app-footer')).toBeInTheDocument()
  })
})

describe('AuthenticatedLayout', () => {
  it('renders header, sidebar, main, status bar and footer around the outlet', async () => {
    authenticate('ADMIN')
    installFetchStub({
      '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
      '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
    })

    renderWithProviders(
      '/',
      <AuthenticatedLayout />,
    )

    expect(screen.getByRole('banner', { name: /cabecera de la aplicacion/i })).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: /navegacion principal/i })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getAllByText(/capacidad predictiva evaluada/i).length).toBeGreaterThan(0)
    expect(screen.getByTestId('app-footer')).toBeInTheDocument()
  })
})

describe('Sidebar', () => {
  it('groups navigation items and filters them by role', () => {
    authenticate('VIEWER')
    installFetchStub({})
    renderWithProviders('/dashboard', <AuthenticatedLayout />)

    const nav = screen.getByRole('navigation', { name: /secciones/i })
    expect(within(nav).getByRole('link', { name: /dashboard/i })).toHaveAttribute('href', '/dashboard')
    expect(within(nav).getByRole('link', { name: /mercado/i })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /auditoria/i })).not.toBeInTheDocument()
  })

  it('marks the active route with an accessible current state via aria-current', async () => {
    authenticate('ANALYST')
    installFetchStub({})
    renderWithProviders('/metrics', <AuthenticatedLayout />)
    const link = await screen.findByRole('link', { name: /metricas/i })
    expect(link.className).toMatch(/bg-accent-cyan-soft/)
  })

  it('declares the minimum role for each entry in NAV_GROUPS', () => {
    const all = NAV_GROUPS.flatMap((group) => group.items)
    expect(all.length).toBeGreaterThan(0)
    for (const item of all) {
      expect(['VIEWER', 'ANALYST', 'ADMIN']).toContain(item.minimum)
    }
    expect(all.find((item) => item.to === '/admin')?.minimum).toBe('ADMIN')
    expect(all.find((item) => item.to === '/experiments')?.minimum).toBe('ANALYST')
    expect(all.find((item) => item.to === '/market')?.minimum).toBe('VIEWER')
  })
})

describe('Sidebar (isolated)', () => {
  it('collapses and expands with a keyboard-reachable control', async () => {
    authenticate('ADMIN')
    installFetchStub({})
    renderWithProviders('/dashboard', <AuthenticatedLayout />)

    const toggle = await screen.findByRole('button', { name: /contraer menu lateral/i })
    await userEvent.click(toggle)
    expect(await screen.findByRole('button', { name: /expandir menu lateral/i })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('Header', () => {
  it('shows the symbol, the price and the connection state', async () => {
    authenticate('VIEWER')
    installFetchStub({
      '/api/v1/market/latest': () =>
        jsonResponse({ json: { symbol: 'XMR-USD', price: 172.44, changePercent: 2.5 } }),
      '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
    })
    renderWithProviders('/dashboard', <AuthenticatedLayout />)

    const header = screen.getByRole('banner', { name: /cabecera de la aplicacion/i })
    await waitFor(() => expect(within(header).getByTestId('api-connection-status')).toHaveTextContent('En linea'))
    expect(within(header).getByText('XMR-USD')).toBeInTheDocument()
  })

  it('shows a "sin conexion" state when the quote request fails', async () => {
    authenticate('VIEWER')
    installFetchStub({
      '/api/v1/market/latest': () => new Response('{"status":503}', { status: 503, headers: { 'content-type': 'application/json' } }),
      '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
    })
    renderWithProviders('/dashboard', <AuthenticatedLayout />)
    await waitFor(() =>
      expect(screen.getByTestId('api-connection-status')).toHaveTextContent(/sin conexion/i),
    )
  })

  it('exposes a notifications menu with a labelled toggle', async () => {
    authenticate('VIEWER')
    installFetchStub({
      '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
      '/api/v1/notifications': () =>
        jsonResponse({
          json: {
            items: [{ id: 'n-1', title: 'Corrida finalizada', severity: 'SUCCESS', createdAt: new Date().toISOString(), read: false }],
            page: 0,
            size: 5,
            total: 1,
            totalPages: 1,
          },
        }),
    })
    renderWithProviders('/dashboard', <AuthenticatedLayout />)

    const toggle = await screen.findByRole('button', { name: /notificaciones, 1 sin leer/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(await screen.findByRole('menu', { name: /notificaciones/i })).toBeInTheDocument()
    expect(screen.getByText('Corrida finalizada')).toBeInTheDocument()
  })
})

describe('ForbiddenPanel (403)', () => {
  it('explains the required role and offers a way out, keeping the footer', () => {
    renderWithProviders(
      '/admin',
      <AuthenticatedLayout />,
    )
    useAuthStore.setState({ status: 'authenticated', user: makeUser({ role: 'VIEWER' }) })
    renderWithProviders('/admin', <ForbiddenPanel required="ADMIN" actual="VIEWER" />)

    expect(screen.getByText(/error 403/i)).toBeInTheDocument()
    expect(screen.getByText(/no tienes acceso a esta seccion/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /volver al dashboard/i })).toHaveAttribute('href', '/dashboard')
  })
})

describe('Accessibility basics across the real app', () => {
  it('landing page has exactly one h1 and a main landmark', () => {
    installFetchStub({})
    renderApp('/')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
  })

  it('login form is fully labelled and keyboard reachable', async () => {
    installFetchStub({})
    renderApp('/login')

    const email = screen.getByLabelText(/correo electronico/i)
    const password = screen.getByLabelText(/contrasena/i)
    expect(email).toHaveAttribute('type', 'email')
    expect(password).toHaveAttribute('type', 'password')

    await userEvent.click(email)
    await userEvent.tab()
    expect(password).toHaveFocus()
  })

  it('every input on the login page has an accessible name', () => {
    installFetchStub({})
    renderApp('/login')
    for (const input of screen.getAllByRole('textbox')) {
      expect(input).toHaveAccessibleName()
    }
  })

  it('sidebar navigation is grouped under a labelled nav landmark', () => {
    authenticate('ADMIN')
    installFetchStub({})
    renderApp('/dashboard')
    expect(screen.getByRole('complementary', { name: /navegacion principal/i })).toBeInTheDocument()
  })
})
