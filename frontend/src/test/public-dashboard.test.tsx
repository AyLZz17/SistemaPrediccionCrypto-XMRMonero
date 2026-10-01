import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authenticate, makeUser, renderApp } from './testUtils'
import { errorResponse, installFetchStub, jsonResponse, type StubCall } from './fetchStub'
import { useAuthStore } from '../store/authStore'

/**
 * PRIMER PANTALLA + SEGURIDAD DE LA SUPERFICIE ANONIMA (T-043).
 *
 * El encargo exige que `/` sea el dashboard publico, con resumen, metricas,
 * comparacion, estado de modelos, aviso legal y limitaciones; que la esquina
 * superior derecha muestre las tres acciones anonimas y CAMBIE al autenticar;
 * que las funciones avanzadas queden tras la sesion; y que todo siga siendo
 * usable (pie, accesibilidad, responsive) aunque el backend falle.
 */

const EXPECTED_FOOTER =
  'Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.'

const PUBLIC_SUMMARY = {
  symbol: 'XMR-USD',
  price: 160.5,
  previousClose: 158,
  change: 2.5,
  changePercent: 1.58,
  high: 162,
  low: 157.1,
  volume: 1234.5,
  marketTime: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T06:15:00Z',
  source: 'yahoo-finance',
}

const PUBLIC_STATUS = {
  generatedAt: '2026-10-01T06:15:00Z',
  dataUpdatedAt: '2026-10-01T06:15:00Z',
  dataPoints: 1500,
  models: 5,
  champions: 3,
  experimentCode: 'E-07-gru',
  experimentStatus: 'SUCCEEDED',
  experimentUpdatedAt: '2026-09-30T12:00:00Z',
  legalVersion: '2026-10-01',
}

const PUBLIC_METRICS = {
  experimentCode: 'E-07-gru',
  available: true,
  best: { mae: 5.1, rmse: 7.2, mape: 3.4, directionAccuracy: 0.61 },
  validation: { mae: 5.1, rmse: 7.2, mape: 3.4, directionAccuracy: 0.61 },
  test: { mae: 6.0, rmse: 8.1, mape: 3.9, directionAccuracy: 0.58 },
}

const PUBLIC_COMPARISON = [
  { label: 'gru_base', family: 'GRU', isChampion: true, metrics: { mae: 5.1, rmse: 7.2, mape: 3.4, directionAccuracy: 0.61 } },
  { label: 'arima_base', family: 'ARIMA', isChampion: false, metrics: { mae: 8.4, rmse: 11.2, mape: 5.6, directionAccuracy: 0.49 } },
]

const PUBLIC_MODELS = [
  { name: 'gru_base', family: 'GRU', task: 'REGRESSION', hasChampion: true },
  { name: 'arima_base', family: 'ARIMA', task: 'REGRESSION', hasChampion: false },
]

const PUBLIC_SERIES = [
  { date: '2026-09-28', open: 155, high: 158, low: 154, close: 157.2, volume: 900, change: 2.2, changePercent: 1.42 },
  { date: '2026-09-29', open: 157.2, high: 161, low: 156.5, close: 160.1, volume: 950, change: 2.9, changePercent: 1.85 },
]

interface StubOptions {
  /** Metrics answer with `available: false` (no completed run yet). */
  withoutRuns?: boolean
  /** Summary endpoint answers 404 (data not ingested yet). */
  summaryError?: boolean
}

function stubPublic(options: StubOptions = {}): { calls: StubCall[] } {
  const { calls } = installFetchStub({
    '/api/v1/public/summary': () =>
      options.summaryError
        ? errorResponse(404, { code: 'MARKET_DATA_NOT_FOUND', message: 'Sin datos de mercado' })
        : jsonResponse({ json: PUBLIC_SUMMARY }),
    '/api/v1/public/series': () => jsonResponse({ json: PUBLIC_SERIES }),
    '/api/v1/public/models': () => jsonResponse({ json: PUBLIC_MODELS }),
    '/api/v1/public/metrics': () =>
      jsonResponse({
        json: options.withoutRuns
          ? { experimentCode: null, available: false, best: null, validation: null, test: null }
          : PUBLIC_METRICS,
      }),
    '/api/v1/public/comparison': () =>
      jsonResponse({ json: options.withoutRuns ? [] : PUBLIC_COMPARISON }),
    '/api/v1/public/status': () =>
      jsonResponse({
        json: options.withoutRuns
          ? { ...PUBLIC_STATUS, experimentCode: null, experimentStatus: null, experimentUpdatedAt: null }
          : PUBLIC_STATUS,
      }),
    '/api/v1/market/latest': () => jsonResponse({ json: PUBLIC_SUMMARY }),
    '/api/v1/auth/me': () => jsonResponse({ json: makeUser({ role: 'VIEWER' }) }),
  })
  return { calls }
}

describe('dashboard publico como primera pantalla', () => {
  it('renderiza anonimo con resumen, metricas, comparacion y estado', async () => {
    stubPublic()
    renderApp('/')

    expect(screen.getByTestId('public-dashboard')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      /capacidad predictiva evaluada/i,
    )

    // Resumen de mercado visible sin sesion.
    expect(await screen.findByText('Precio XMR-USD')).toBeInTheDocument()
    expect(screen.getByText('Variación')).toBeInTheDocument()
    expect(screen.getByText('Máximo / mínimo')).toBeInTheDocument()

    // Metricas publicas visibles con sus valores. Se acota a su propia seccion
    // porque la tabla de comparacion repite exactamente las mismas cabeceras.
    const metricsBlock = await screen.findByTestId('public-metrics')
    expect(within(metricsBlock).getByText('MAE')).toBeInTheDocument()
    expect(within(metricsBlock).getByText('RMSE')).toBeInTheDocument()
    expect(within(metricsBlock).getByText('MAPE')).toBeInTheDocument()
    expect(within(metricsBlock).getByText('Dirección')).toBeInTheDocument()
    expect(within(metricsBlock).getByText(/Corrida E-07-gru/i)).toBeInTheDocument()

    // Comparacion: fila de la tabla accesible + presencia del bloque del grafico.
    const comparisonBlock = await screen.findByTestId('comparison')
    expect(within(comparisonBlock).getByText('gru_base', { selector: 'th' })).toBeInTheDocument()
    expect(within(comparisonBlock).getByText('arima_base', { selector: 'th' })).toBeInTheDocument()

    // Estado general de modelos con su marca de campeon.
    const modelsBlock = await screen.findByTestId('models-list')
    expect(await within(modelsBlock).findByText('gru_base', { selector: 'li span' })).toBeInTheDocument()
    expect(within(modelsBlock).getByText('campeón')).toBeInTheDocument()

    // Estado de actualizacion con la fecha real de la ultima ingesta.
    expect(await screen.findByTestId('data-updated-at')).toHaveTextContent(/6:15/)
    expect(screen.getByText(/Documentos legales/i)).toBeInTheDocument()
    expect(screen.getByText(/v2026-10-01/)).toBeInTheDocument()
  })

  it('solo consulta rutas publicas: ni experimentos, ni trabajos, ni predicciones, ni admin', async () => {
    const { calls } = stubPublic()
    renderApp('/')
    await waitFor(() => expect(screen.getByText('Precio XMR-USD')).toBeInTheDocument())

    const probed = ['/experiments', '/jobs', '/predictions', '/admin', '/audit', '/users']
    for (const call of calls) {
      for (const path of probed) {
        expect(call.url).not.toContain(path)
      }
    }
    // Y el trafico de datos sale por el prefijo publico.
    expect(calls.some((call) => call.url.startsWith('/api/v1/public/'))).toBe(true)
  })

  it('la esquina superior derecha ofrece las tres acciones anonimas', () => {
    stubPublic()
    renderApp('/')

    // Se acota a la cabecera: el propio panel de "funciones avanzadas" repite
    // un enlace de registro dentro de la vista anonima.
    const banner = screen.getByRole('banner')
    expect(within(banner).getAllByRole('link', { name: /^iniciar sesión$/i })).toHaveLength(1)
    expect(within(banner).getByRole('link', { name: /^registrarse$/i })).toHaveAttribute(
      'href',
      '/register',
    )
    expect(within(banner).getByTestId('google-header-oauth-button')).toHaveAccessibleName(
      /iniciar sesión con google/i,
    )
    expect(within(banner).queryByTestId('google-oauth-button')).not.toBeInTheDocument()
  })

  it('declara las funciones avanzadas como bloqueadas y las dirige al login', async () => {
    stubPublic()
    renderApp('/')

    const locked = screen.getByTestId('advanced-locked')
    expect(within(locked).getByText(/funciones avanzadas: requieren sesión/i)).toBeInTheDocument()
    expect(within(locked).getByText('Experimentos')).toBeInTheDocument()
    expect(within(locked).getByText('Predicciones')).toBeInTheDocument()

    // Nada lleva a una ruta privada desde la vista anonima: ni por nombre ni
    // por destino (un `<Link>` a /admin, /experiments o /jobs no existe).
    expect(screen.queryByRole('link', { name: /experimentos/i })).not.toBeInTheDocument()
    for (const selector of [
      'a[href="/admin"]',
      'a[href="/experiments"]',
      'a[href="/jobs"]',
      'a[href="/audit"]',
      'a[href="/predictions"]',
    ]) {
      expect(document.querySelector(selector)).toBeNull()
    }

    const user = userEvent.setup()
    await user.click(within(locked).getByRole('button', { name: /iniciar sesión/i }))
    expect(await screen.findByRole('heading', { level: 1, name: /iniciar sesión/i })).toBeInTheDocument()
  })

  it('sigue mostrando el aviso legal y el pie en la primera pantalla', async () => {
    stubPublic()
    renderApp('/')

    // El aviso aparece dos veces a proposito: resumido bajo el encabezado y
    // completo en la seccion de limitaciones.
    expect((await screen.findAllByText(/no es asesoria financiera/i)).length).toBeGreaterThan(0)
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
  })

  it('mantiene el pie en /about (landing movida desde la raiz)', () => {
    stubPublic()
    renderApp('/about')
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
    expect(screen.getByRole('link', { name: /terminos y condiciones/i })).toBeInTheDocument()
  })
})

describe('metricas honestas y errores de la superficie anonima', () => {
  it('sin corridas terminadas declara la ausencia en vez de inventar cifras', async () => {
    stubPublic({ withoutRuns: true })
    renderApp('/')

    expect(await screen.findByText(/todavía no hay corridas publicadas/i)).toBeInTheDocument()
    expect(screen.queryByText('Corrida E-07-gru')).not.toBeInTheDocument()
    // El encargo pide el estado de la ultima corrida: sin corridas, se dice.
    expect(screen.getByText(/sin corridas/i)).toBeInTheDocument()
  })

  it('un fallo del backend no deja la primera pantalla en blanco', async () => {
    stubPublic({ summaryError: true })
    renderApp('/')

    expect(await screen.findByText(/no se pudo leer el resumen de mercado/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })
})

describe('cabecera reactiva', () => {
  it('autenticada muestra identidad, rol, configuracion, dashboard y cierre de sesion', async () => {
    stubPublic()
    authenticate('VIEWER')
    renderApp('/')

    const identity = await screen.findByTestId('public-identity')
    expect(identity).toHaveTextContent(/Ana Lector/)
    expect(identity).toHaveTextContent(/VIEWER/)
    // En pantalla pequena el bloque colapsa para no desbordar la cabecera.
    expect(identity.className).toMatch(/^hidden/)
    expect(identity.className).toMatch(/sm:flex/)
    const banner = screen.getByRole('banner')
    expect(within(banner).getByRole('link', { name: /^configuración$/i })).toHaveAttribute(
      'href',
      '/account',
    )
    expect(within(banner).getByRole('link', { name: /^dashboard$/i })).toHaveAttribute(
      'href',
      '/dashboard',
    )
    expect(within(banner).getByRole('button', { name: /cerrar sesión/i })).toBeInTheDocument()

    // Las acciones anonimas de la cabecera desaparecen: no se ofrece iniciar
    // sesion a quien ya la tiene (el CTA del panel de bloqueo de la propia
    // pagina es contenido, no accion de la cabecera).
    expect(within(banner).queryByRole('link', { name: /^registrarse$/i })).not.toBeInTheDocument()
    expect(within(banner).queryByRole('link', { name: /^iniciar sesión$/i })).not.toBeInTheDocument()
    expect(screen.queryByTestId('google-header-oauth-button')).not.toBeInTheDocument()
  })

  it('al cerrar sesion vuelve a la vista anonima', async () => {
    stubPublic()
    authenticate('VIEWER')
    renderApp('/')

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /cerrar sesión/i }))

    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    const banner = screen.getByRole('banner')
    expect(within(banner).getByRole('link', { name: /^registrarse$/i })).toBeInTheDocument()
  })
})

describe('accesibilidad y responsive de la primera pantalla', () => {
  it('tiene un unico h1, landmarks y secciones con nombre accesible', async () => {
    stubPublic()
    renderApp('/')

    await waitFor(() => expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1))
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()

    for (const section of screen.getAllByRole('region')) {
      expect(section).toHaveAccessibleName()
    }
    for (const button of screen.getAllByRole('button')) {
      expect(button).toHaveAccessibleName()
    }
  })

  it('usa rejillas y menus que colapsan en pantalla pequena', async () => {
    stubPublic()
    renderApp('/')

    const summary = await screen.findByTestId('summary')
    expect(summary.className).toMatch(/sm:grid-cols-2/)
    expect(summary.className).toMatch(/xl:grid-cols-4/)

    // La navegacion de la cabecera envuelve en pantalla pequena para que las
    // tres acciones anonimas no desborden el encabezado.
    const nav = screen.getByRole('banner').querySelector('nav')
    expect(nav?.className).toMatch(/flex-wrap/)
  })
})
