import { Link } from 'react-router-dom'
import { Disclaimer } from '../components/common/Disclaimer'
import { Badge, Button, MetricCard, Panel, StatusDot } from '../components/ui'
import { useAuthStore } from '../store/authStore'

const FEATURES = [
  {
    title: 'Comparamos varios modelos',
    body: 'Probamos LSTM y GRU frente a media móvil, regresión lineal y ARIMA con los mismos datos y fechas (R-01, R-05).',
  },
  {
    title: 'Métricas obligatorias',
    body: 'MAE, RMSE, MAPE y proporción de aciertos de dirección (R-07), con media y desviación sobre múltiples semillas (R-08).',
  },
  {
    title: 'Partición cronológica estricta',
    body: 'Sin mezcla aleatoria: train, validación y prueba se ordenan por fecha y el ajuste nunca toca la prueba (R-01, R-04).',
  },
  {
    title: 'Campeón elegido por validación',
    body: 'El modelo campeón se selecciona con métricas de validación; la prueba solo se reporta (R-24). Un resultado negativo se publica tal cual (R-09).',
  },
]

const PIPELINE = [
  { step: '01', label: 'Ingesta', detail: 'Snapshot CSV con checksum' },
  { step: '02', label: 'Partición', detail: '70 / 15 / 15 cronológica' },
  { step: '03', label: 'Entrenamiento', detail: 'Múltiples semillas' },
  { step: '04', label: 'Evaluación', detail: 'MAE · RMSE · MAPE · dirección' },
  { step: '05', label: 'Informe', detail: 'Sin backtesting' },
]

const FAMILIES = [
  { name: 'LSTM', kind: 'Recurrente', note: 'Red de memoria a corto y largo plazo; candidata principal.' },
  { name: 'GRU', kind: 'Recurrente', note: 'Alternativa recurrente con menos parámetros.' },
  { name: 'Media móvil', kind: 'Línea base', note: 'Referencia ingenua de suavizado.' },
  { name: 'Regresión lineal', kind: 'Línea base', note: 'Referencia lineal sobre la ventana.' },
  { name: 'ARIMA', kind: 'Línea base', note: 'Referencia estadística con pronóstico rodante de un paso.' },
]

export default function LandingPage() {
  const authenticated = useAuthStore((state) => state.status === 'authenticated')

  return (
    <div className="space-y-10">
      {/* ---------------------------------------------------------- portada */}
      <section className="overflow-hidden rounded border border-hairline-subtle bg-surface-1">
        <span aria-hidden="true" className="block h-1 bg-brand" />
        <div className="max-w-3xl p-6 sm:p-10">
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge tone="neutral">
              Capacidad predictiva evaluada
            </Badge>
            <Badge tone="neutral">XMR-USD</Badge>
            <Badge tone="neutral">LSTM / GRU vs. ARIMA</Badge>
          </div>
          <h1 className="mt-4 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
            Capacidad predictiva evaluada para Monero
          </h1>
          <p className="mt-3 max-w-2xl text-[15px] leading-normal text-ink-secondary">
            XMR-Forecast mide, con particiones cronológicas rigurosas, cuánta capacidad predictiva tienen los
            modelos recurrentes frente a las líneas base clásicas sobre la serie diaria de XMR-USD. Publicamos
            el resultado, incluso cuando no es favorable.
          </p>
          <div className="mt-6 flex flex-wrap gap-2.5">
            <Link to={authenticated ? '/dashboard' : '/register'}>
              <Button size="lg">{authenticated ? 'Ir al dashboard' : 'Crear cuenta gratuita'}</Button>
            </Link>
            <Link to={authenticated ? '/models' : '/login'}>
              <Button size="lg" variant="secondary">
                {authenticated ? 'Ver comparativa' : 'Iniciar sesión'}
              </Button>
            </Link>
          </div>
          <div className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-hairline-subtle pt-4">
            <StatusDot tone="success" label="HTTPS obligatorio" />
            <StatusDot tone="idle" label="Trazabilidad por requestId" />
            <StatusDot tone="idle" label="Sin simulación de operaciones" />
          </div>
        </div>
      </section>

      <section aria-labelledby="landing-disclaimer">
        <h2 id="landing-disclaimer" className="sr-only">
          Aviso legal
        </h2>
        <Disclaimer variant="full" />
      </section>

      {/* ------------------------------------------------------ metodología */}
      <section aria-labelledby="landing-pipeline" className="space-y-4">
        <div>
          <p className="label-caps-ticked">Metodología</p>
          <h2 id="landing-pipeline" className="mt-1.5 text-2xl font-semibold tracking-tight text-ink">
            Del dato histórico al informe reproducible
          </h2>
        </div>
        <ol className="grid gap-px overflow-hidden rounded border border-hairline-subtle bg-hairline-subtle sm:grid-cols-2 lg:grid-cols-5">
          {PIPELINE.map((item) => (
            <li key={item.step} className="bg-deep p-4">
              <p className="font-mono text-xs tabular-nums text-brand-strong">{item.step}</p>
              <p className="mt-1.5 text-sm font-semibold text-ink">{item.label}</p>
              <p className="mt-1 text-xs leading-normal text-ink-muted">{item.detail}</p>
            </li>
          ))}
        </ol>
      </section>

      {/* -------------------------------------------------------- familias */}
      <section aria-labelledby="landing-families" className="space-y-4">
        <div>
          <p className="label-caps-ticked">Modelos evaluados</p>
          <h2 id="landing-families" className="mt-1.5 text-2xl font-semibold tracking-tight text-ink">
            Recurrentes frente a líneas base, en igualdad de condiciones
          </h2>
          <p className="mt-1.5 max-w-3xl text-sm leading-normal text-ink-secondary">
            Todas las familias se evalúan sobre la misma partición y las mismas fechas. Las líneas base
            nunca se ocultan: son la referencia contra la que se mide cualquier mejora.
          </p>
        </div>
        <ul className="divide-y divide-hairline-subtle rounded border border-hairline-subtle bg-surface-table">
          {FAMILIES.map((family) => (
            <li key={family.name} className="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-3 hover:bg-surface-2">
              <span className="min-w-36 text-sm font-semibold text-ink">{family.name}</span>
              <Badge tone={family.kind === 'Línea base' ? 'neutral' : 'brand'}>{family.kind}</Badge>
              <span className="w-full text-[13px] text-ink-secondary sm:w-auto sm:flex-1">{family.note}</span>
            </li>
          ))}
        </ul>
      </section>

      {/* ------------------------------------------------------ principios */}
      <section aria-labelledby="landing-features" className="space-y-4">
        <div>
          <p className="label-caps-ticked">Principios</p>
          <h2 id="landing-features" className="mt-1.5 text-2xl font-semibold tracking-tight text-ink">
            Lo que ofrece la plataforma
          </h2>
        </div>
        <div className="grid gap-px overflow-hidden rounded border border-hairline-subtle bg-hairline-subtle md:grid-cols-2">
          {FEATURES.map((feature, index) => (
            <div key={feature.title} className="bg-deep p-5">
              <p className="font-mono text-[11px] tabular-nums text-ink-muted">{String(index + 1).padStart(2, '0')}</p>
              <h3 className="mt-1.5 text-[15px] font-semibold text-ink">{feature.title}</h3>
              <p className="mt-1.5 text-[13px] leading-normal text-ink-secondary">{feature.body}</p>
            </div>
          ))}
        </div>
      </section>

      {/* -------------------------------------------------------- métricas */}
      <section aria-labelledby="landing-cta" className="space-y-4">
        <div>
          <p className="label-caps-ticked">Métricas de referencia</p>
          <h2 id="landing-cta" className="mt-1.5 text-2xl font-semibold tracking-tight text-ink">
            Lo que verás dentro de la plataforma
          </h2>
        </div>
        <Panel>
          <div className="grid gap-x-6 gap-y-5 sm:grid-cols-2 lg:grid-cols-4">
            <MetricCard label="MAE" value="USD" hint="Error absoluto medio en dólares" tone="idle" />
            <MetricCard label="RMSE" value="USD" hint="Penaliza errores grandes" tone="idle" />
            <MetricCard label="MAPE" value="%" hint="Error porcentual medio" tone="idle" />
            <MetricCard
              label="Dirección"
              value="%"
              hint="Proporción de aciertos sube / baja"
              tone="idle"
            />
          </div>
        </Panel>
        <section aria-label="Acceso a la plataforma" className="overflow-hidden rounded border border-hairline-default bg-surface-2">
          <span aria-hidden="true" className="block h-0.5 bg-brand" />
          <div className="flex flex-col items-start justify-between gap-4 p-5 sm:flex-row sm:items-center">
            <div>
              <h3 className="text-base font-semibold text-ink">Empieza a consultar la capacidad del sistema</h3>
              <p className="mt-1 max-w-2xl text-[13px] leading-normal text-ink-secondary">
                Crea una cuenta para ver el mercado, generar pronósticos y comparar modelos. Los roles VIEWER,
                ANALYST y ADMIN controlan qué se puede ver y ejecutar.
              </p>
            </div>
            <Link to={authenticated ? '/dashboard' : '/register'} className="shrink-0">
              <Button size="lg">{authenticated ? 'Abrir panel' : 'Crear cuenta'}</Button>
            </Link>
          </div>
        </section>
      </section>
    </div>
  )
}
