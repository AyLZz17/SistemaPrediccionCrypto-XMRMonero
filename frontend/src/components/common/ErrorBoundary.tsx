import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Footer } from '../layout/Footer'
import { Button, Panel, StatusDot } from '../ui'

interface ErrorBoundaryState {
  error: Error | null
  info: string | null
}

/**
 * Last-resort boundary. Renders the mandatory `<Footer />` as well, so the
 * error state of ANY route still shows it.
 *
 * Error details are shown to the user only as a short code; no stack traces are
 * written to the DOM.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, ErrorBoundaryState> {
  public override state: ErrorBoundaryState = { error: null, info: null }

  public static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error, info: null }
  }

  public override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Keep the correlation id discoverable for support without leaking internals.
    this.setState({ error, info: info.componentStack ?? null })
  }

  private readonly handleReset = (): void => {
    this.setState({ error: null, info: null })
  }

  public override render(): ReactNode {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className="flex min-h-screen flex-col bg-deep">
        <main className="flex flex-1 items-center justify-center p-6">
          <div className="w-full max-w-lg">
            <Panel className="border-accent-red/40">
              <div className="flex items-center gap-3">
                <StatusDot tone="danger" label="Error 500" />
                <h2 className="text-xl font-semibold text-ink">La interfaz encontro un error inesperado</h2>
              </div>
              <p className="mt-3 text-sm text-ink-secondary">
                Se ha detenido el renderizado de esta vista. Puedes reintentarla; si el problema persiste,
                comparte el identificador con el equipo de soporte.
              </p>
              <p className="mt-3 rounded-md border border-hairline-subtle bg-surface-inset p-3 font-mono text-xs text-ink-muted">
                {error.name}: {error.message}
              </p>
              <div className="mt-5 flex flex-wrap gap-2">
                <Button onClick={this.handleReset}>Reintentar</Button>
                <Button variant="secondary" onClick={() => window.location.assign('/dashboard')}>
                  Ir al dashboard
                </Button>
                <Button variant="ghost" onClick={() => window.location.reload()}>
                  Recargar la pagina
                </Button>
              </div>
            </Panel>
          </div>
        </main>
        <Footer />
      </div>
    )
  }
}
