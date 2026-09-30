import { Link } from 'react-router-dom'
import { Footer } from '../components/layout/Footer'
import { Button, Panel, StatusDot } from '../components/ui'

/** `*` route. Renders its own footer because it sits outside both layouts. */
export default function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col bg-deep">
      <main className="flex flex-1 items-center justify-center p-6">
        <div className="w-full max-w-lg">
          <Panel className="border-accent-amber/35 text-center">
            <div className="flex justify-center">
              <StatusDot tone="warning" label="HTTP 404" pulse />
            </div>
            <p className="mt-4 font-mono text-5xl font-semibold tabular-nums text-ink">404</p>
            <h1 className="mt-2 text-xl font-semibold text-ink">Ruta no encontrada</h1>
            <p className="mt-2 text-sm text-ink-secondary">
              La direccion que buscas no existe en XMR-Forecast. Comprueba el enlace o vuelve al panel principal.
            </p>
            <div className="mt-6 flex flex-wrap justify-center gap-2">
              <Link to="/">
                <Button>Volver al inicio</Button>
              </Link>
              <Link to="/dashboard">
                <Button variant="secondary">Ir al dashboard</Button>
              </Link>
            </div>
          </Panel>
        </div>
      </main>
      <Footer />
    </div>
  )
}
