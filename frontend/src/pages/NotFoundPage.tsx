import { Link } from 'react-router-dom'
import { Footer } from '../components/layout/Footer'
import { BrandMark } from '../components/layout/BrandMark'
import { Button, Panel, StatusDot } from '../components/ui'

/** `*` route. Renders its own footer because it sits outside both layouts. */
export default function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col bg-deep">
      <main className="flex flex-1 items-center justify-center p-6">
        <div className="w-full max-w-lg">
          <Panel tone="strong">
            <div className="mb-4 flex items-center justify-between gap-3 border-b-2 border-hairline-strong pb-3">
              <BrandMark size={30} />
              <StatusDot tone="warning" label="HTTP 404" pulse />
            </div>
            <p className="font-mono text-6xl font-semibold tabular-nums leading-none text-ink">404</p>
            <h1 className="mt-2 text-xl font-semibold tracking-tight text-ink">Ruta no encontrada</h1>
            <p className="mt-2 text-sm leading-normal text-ink-secondary">
              La direccion que buscas no existe en XMR-Forecast. Comprueba el enlace o vuelve al panel principal.
            </p>
            <div className="mt-5 flex flex-wrap gap-2 border-t border-hairline-subtle pt-4">
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
