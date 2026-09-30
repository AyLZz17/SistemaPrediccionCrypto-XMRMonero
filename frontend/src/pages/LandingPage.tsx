import { Link } from 'react-router-dom'
import { Disclaimer } from '../components/common/Disclaimer'
import { Badge, Button, MetricCard, Panel, StatusDot } from '../components/ui'
import { useAuthStore } from '../store/authStore'

const FEATURES = [
  {
    title: 'LSTM y GRU frente a baselines',
    body: 'Comparamos modelos recurrentes contra media movil, regresion lineal y ARIMA sobre la misma particion cronologica (R-01, R-05).',
  },
  {
    title: 'Metricas obligatorias',
    body: 'MAE, RMSE, MAPE y proporcion de aciertos de direccion (R-07), con media y desviacion sobre multiples semillas (R-08).',
  },
  {
    title: 'Particion cronologica estricta',
    body: 'Sin mezcla aleatoria: train, validacion y prueba se ordenan por fecha y el ajuste nunca toca la prueba (R-01, R-04).',
  },
  {
    title: 'Campeon elegido por validacion',
    body: 'El modelo campeon se selecciona con metricas de validacion; la prueba solo se reporta (R-24). Un resultado negativo se publica tal cual (R-09).',
  },
]

const PIPELINE = [
  { step: '01', label: 'Ingesta', detail: 'Snapshot CSV con checksum' },
  { step: '02', label: 'Particion', detail: '70 / 15 / 15 cronologica' },
  { step: '03', label: 'Entrenamiento', detail: 'Multiples semillas' },
  { step: '04', label: 'Evaluacion', detail: 'MAE · RMSE · MAPE · direccion' },
  { step: '05', label: 'Informe', detail: 'Sin backtesting' },
]

export default function LandingPage() {
  const authenticated = useAuthStore((state) => state.status === 'authenticated')

  return (
    <div className="space-y-14">
      <section className="relative overflow-hidden rounded-xl border border-hairline-subtle bg-surface-1/70 p-8 sm:p-12">
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 grid-lines opacity-40" />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -top-24 right-10 h-64 w-64 rounded-full bg-accent-cyan/10 blur-3xl"
        />
        <div className="relative max-w-3xl">
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone="active" dot pulse>
              Operacion en vivo
            </Badge>
            <Badge tone="neutral">XMR-USD</Badge>
            <Badge tone="neutral">LSTM / GRU vs. ARIMA</Badge>
          </div>
          <h1 className="mt-5 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
            Capacidad predictiva evaluada para Monero
          </h1>
          <p className="mt-4 max-w-2xl text-lg leading-normal text-ink-secondary">
            XMR-Forecast mide, con particiones cronologicas rigorousas, cuanta capacidad predictiva tienen los
            modelos recurrentes frente a las lineas base clasicas sobre la serie diaria de XMR-USD. Publicamos
            el resultado, incluso cuando no es favorable.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to={authenticated ? '/dashboard' : '/register'}>
              <Button size="lg">{authenticated ? 'Ir al dashboard' : 'Crear cuenta gratuita'}</Button>
            </Link>
            <Link to={authenticated ? '/models' : '/login'}>
              <Button size="lg" variant="secondary">
                {authenticated ? 'Ver comparativa' : 'Iniciar sesion'}
              </Button>
            </Link>
          </div>
          <div className="mt-8 flex flex-wrap items-center gap-x-6 gap-y-2">
            <StatusDot tone="success" label="HTTPS obligatorio" />
            <StatusDot tone="active" label="Trazabilidad por requestId" />
            <StatusDot tone="idle" label="Sin simulacion de operaciones" />
          </div>
        </div>
      </section>

      <section aria-labelledby="landing-disclaimer">
        <h2 id="landing-disclaimer" className="sr-only">
          Aviso legal
        </h2>
        <Disclaimer variant="full" />
      </section>

      <section aria-labelledby="landing-pipeline" className="space-y-5">
        <div>
          <p className="label-caps">Metodologia</p>
          <h2 id="landing-pipeline" className="mt-1 text-2xl font-semibold text-ink">
            Del dato historico al informe reproducible
          </h2>
        </div>
        <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {PIPELINE.map((item) => (
            <li key={item.step}>
              <Panel className="h-full">
                <p className="font-mono text-xs text-accent-cyan">{item.step}</p>
                <p className="mt-2 text-sm font-semibold text-ink">{item.label}</p>
                <p className="mt-1 text-xs text-ink-muted">{item.detail}</p>
              </Panel>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="landing-features" className="space-y-5">
        <div>
          <p className="label-caps">Principios</p>
          <h2 id="landing-features" className="mt-1 text-2xl font-semibold text-ink">
            Lo que guarantee la plataforma
          </h2>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          {FEATURES.map((feature) => (
            <Panel key={feature.title}>
              <h3 className="text-base font-semibold text-ink">{feature.title}</h3>
              <p className="mt-2 text-sm leading-normal text-ink-secondary">{feature.body}</p>
            </Panel>
          ))}
        </div>
      </section>

      <section aria-labelledby="landing-cta" className="space-y-5">
        <div>
          <p className="label-caps">Metricas de referencia</p>
          <h2 id="landing-cta" className="mt-1 text-2xl font-semibold text-ink">
            Lo que veras dentro de la plataforma
          </h2>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard label="MAE" value="USD" hint="Error absoluto medio en dolares" tone="active" />
          <MetricCard label="RMSE" value="USD" hint="Penaliza errores grandes" tone="active" />
          <MetricCard label="MAPE" value="%" hint="Error porcentual medio" tone="warning" />
          <MetricCard
            label="Direccion"
            value="%"
            hint="Proporcion de aciertos sube / baja"
            tone="success"
          />
        </div>
        <Panel tone="accent" className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center">
          <div>
            <h3 className="text-base font-semibold text-ink">Empieza a consultar la capacidad del sistema</h3>
            <p className="mt-1 text-sm text-ink-secondary">
              Crea una cuenta para ver el mercado, generar pronosticos y comparar modelos. Los roles VIEWER,
              ANALYST y ADMIN controlan que se puede ver y ejecutar.
            </p>
          </div>
          <Link to={authenticated ? '/dashboard' : '/register'} className="shrink-0">
            <Button size="lg">{authenticated ? 'Abrir panel' : 'Crear cuenta'}</Button>
          </Link>
        </Panel>
      </section>
    </div>
  )
}
