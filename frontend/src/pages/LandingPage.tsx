import { Link } from 'react-router-dom'
import { Disclaimer } from '../components/common/Disclaimer'
import { Badge, Button, MetricCard, Section, StatusDot } from '../components/ui'
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

const SPECS: Array<[string, string]> = [
  ['Activo', 'Monero · XMR-USD · velas diarias'],
  ['Tareas', 'Cierre t+1 (regresión) y dirección sube / baja'],
  ['Partición', '70 / 15 / 15 estrictamente cronológica'],
  ['Semillas', 'Mínimo 5 por modelo estocástico'],
  ['Salida', 'Métrica registrada · sin operaciones'],
]

export default function LandingPage() {
  const authenticated = useAuthStore((state) => state.status === 'authenticated')

  return (
    <div className="space-y-10">
      {/* ------------------------------------------------------ masthead */}
      <div className="border-b-2 border-hairline-strong pb-6">
        <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">
          Ficha técnica · XMR-Forecast
        </p>
        <h1 className="mt-2 max-w-3xl font-mono text-3xl font-semibold tracking-tight text-ink sm:text-4xl">
          Capacidad predictiva evaluada para Monero
        </h1>
        <p className="mt-3 max-w-2xl text-[15px] leading-normal text-ink-secondary">
          XMR-Forecast mide, con particiones cronológicas rigurosas, cuánta capacidad predictiva tienen los
          modelos recurrentes frente a las líneas base clásicas sobre la serie diaria de XMR-USD. Publicamos
          el resultado, incluso cuando no es favorable.
        </p>
        <div className="mt-5 flex flex-wrap items-center gap-2.5">
          <Link to={authenticated ? '/dashboard' : '/register'}>
            <Button size="lg">{authenticated ? 'Ir al dashboard' : 'Crear cuenta gratuita'}</Button>
          </Link>
          <Link to={authenticated ? '/models' : '/login'}>
            <Button size="lg" variant="secondary">
              {authenticated ? 'Ver comparativa' : 'Iniciar sesión'}
            </Button>
          </Link>
        </div>
        <dl className="mt-6 grid gap-px border border-hairline-subtle bg-hairline-subtle sm:grid-cols-2 lg:grid-cols-5">
          {SPECS.map(([term, value]) => (
            <div key={term} className="bg-deep px-3.5 py-2.5">
              <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">{term}</dt>
              <dd className="mt-1 text-[13px] leading-snug text-ink">{value}</dd>
            </div>
          ))}
        </dl>
        <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-2">
          <StatusDot tone="success" label="HTTPS obligatorio" />
          <StatusDot tone="idle" label="Trazabilidad por requestId" />
          <StatusDot tone="idle" label="Sin simulación de operaciones" />
          <span className="flex flex-wrap gap-1.5">
            <Badge tone="neutral">XMR-USD</Badge>
            <Badge tone="neutral">LSTM / GRU vs. ARIMA</Badge>
          </span>
        </div>
      </div>

      <section aria-labelledby="landing-disclaimer">
        <h2 id="landing-disclaimer" className="sr-only">
          Aviso legal
        </h2>
        <Disclaimer variant="full" />
      </section>

      {/* ------------------------------------------------------ metodología */}
      <Section
        index="01"
        eyebrow="Metodología"
        title="Del dato histórico al informe reproducible"
      >
        <ol className="divide-y divide-hairline-subtle border-y-2 border-hairline-strong">
          {PIPELINE.map((item) => (
            <li key={item.step} className="flex flex-wrap items-baseline gap-x-5 gap-y-1 py-3">
              <span aria-hidden="true" className="font-mono text-[13px] font-semibold tabular-nums text-brand-strong">{item.step}</span>
              <span className="min-w-36 text-sm font-semibold text-ink">{item.label}</span>
              <span className="text-[13px] text-ink-secondary">{item.detail}</span>
            </li>
          ))}
        </ol>
      </Section>

      {/* -------------------------------------------------------- familias */}
      <Section
        index="02"
        eyebrow="Modelos evaluados"
        title="Recurrentes frente a líneas base, en igualdad de condiciones"
        description="Todas las familias se evalúan sobre la misma partición y las mismas fechas. Las líneas base nunca se ocultan: son la referencia contra la que se mide cualquier mejora."
      >
        <div className="relative w-full overflow-x-auto">
          <table className="w-full border-collapse text-left text-sm">
            <caption className="sr-only">Familias de modelos evaluadas</caption>
            <thead>
              <tr className="border-b-2 border-hairline-strong">
                <th scope="col" className="py-2 pr-4 font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Familia</th>
                <th scope="col" className="py-2 pr-4 font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Tipo</th>
                <th scope="col" className="py-2 font-mono text-[11px] font-semibold uppercase tracking-wide text-ink-secondary">Evaluación</th>
              </tr>
            </thead>
            <tbody>
              {FAMILIES.map((family) => (
                <tr key={family.name} className="border-b border-hairline-subtle last:border-b-0 hover:bg-surface-2">
                  <th scope="row" className="py-2.5 pr-4 text-left font-semibold text-ink">{family.name}</th>
                  <td className="py-2.5 pr-4">
                    <Badge tone={family.kind === 'Línea base' ? 'neutral' : 'brand'}>{family.kind}</Badge>
                  </td>
                  <td className="py-2.5 text-[13px] text-ink-secondary">{family.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ------------------------------------------------------ principios */}
      <Section
        index="03"
        eyebrow="Principios"
        title="Lo que ofrece la plataforma"
      >
        <div className="grid gap-x-10 gap-y-5 md:grid-cols-2">
          {FEATURES.map((feature, position) => (
            <div key={feature.title} className="border-t-2 border-hairline-strong pt-2.5">
              <p className="font-mono text-[11px] tabular-nums text-ink-muted">{String(position + 1).padStart(2, '0')}</p>
              <h3 className="mt-1 text-[15px] font-semibold text-ink">{feature.title}</h3>
              <p className="mt-1 text-[13px] leading-normal text-ink-secondary">{feature.body}</p>
            </div>
          ))}
        </div>
      </Section>

      {/* -------------------------------------------------------- métricas */}
      <Section
        index="04"
        eyebrow="Métricas de referencia"
        title="Lo que verás dentro de la plataforma"
      >
        <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2 lg:grid-cols-4">
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
        <div className="mt-6 flex flex-col items-start justify-between gap-4 border border-hairline-default bg-surface-1 p-5 sm:flex-row sm:items-center">
          <div>
            <h3 className="font-mono text-[13px] font-semibold uppercase tracking-wide text-ink">Acceso a la plataforma</h3>
            <p className="mt-1 max-w-2xl text-[13px] leading-normal text-ink-secondary">
              Crea una cuenta para ver el mercado, generar pronósticos y comparar modelos. Los roles VIEWER,
              ANALYST y ADMIN controlan qué se puede ver y ejecutar.
            </p>
          </div>
          <Link to={authenticated ? '/dashboard' : '/register'} className="shrink-0">
            <Button size="lg">{authenticated ? 'Abrir panel' : 'Crear cuenta'}</Button>
          </Link>
        </div>
      </Section>

    </div>
  )
}
