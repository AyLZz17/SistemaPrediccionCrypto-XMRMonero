import { Link } from 'react-router-dom'
import { Button, Panel } from '../ui'
import { ROLE_LABELS, type Role } from '../../types'

/**
 * 403 view. Rendered *inside* the authenticated layout, so the mandatory footer
 * is present on the forbidden state too.
 */
export function ForbiddenPanel({ required, actual }: { required: Role; actual: Role | null }) {
  return (
    <div className="mx-auto w-full max-w-lg">
      <Panel className="border-accent-amber/40 text-center">
        <p className="font-mono text-xs uppercase tracking-wide text-accent-amber">Error 403 · forbidden</p>
        <h2 className="mt-2 text-xl font-semibold text-ink">No tienes acceso a esta seccion</h2>
        <p className="mt-2 text-sm text-ink-secondary">
          Se requiere rol <span className="font-mono text-ink">{ROLE_LABELS[required]}</span> o superior
          {actual ? (
            <>
              {' '}
              y tu sesion tiene rol <span className="font-mono text-ink">{ROLE_LABELS[actual]}</span>
            </>
          ) : null}
          . Si necesitas acceso, solicita al administrador de la plataforma.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <Link to="/dashboard">
            <Button variant="secondary">Volver al dashboard</Button>
          </Link>
          <Link to="/account">
            <Button variant="ghost">Mi cuenta</Button>
          </Link>
        </div>
      </Panel>
    </div>
  )
}
