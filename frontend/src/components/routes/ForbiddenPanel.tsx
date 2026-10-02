import { Link } from 'react-router-dom'
import { Button, Panel, PanelHeader } from '../ui'
import { ROLE_LABELS, type Role } from '../../types'

/**
 * 403 view. Rendered *inside* the authenticated layout, so the mandatory footer
 * is present on the forbidden state too.
 */
export function ForbiddenPanel({ required, actual }: { required: Role; actual: Role | null }) {
  return (
    <div className="mx-auto w-full max-w-lg">
      <Panel tone="danger">
        <p className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-wide text-accent-amber">
          <span aria-hidden="true" className="inline-block h-3 w-0.5 bg-accent-amber" />
          Error 403 · denegado
        </p>
        <PanelHeader title="No tienes acceso a esta seccion" marker="warn" as="h2" className="mt-3" />
        <p className="text-sm leading-normal text-ink-secondary">
          Se requiere rol <span className="font-mono text-[13px] text-ink">{ROLE_LABELS[required]}</span> o superior
          {actual ? (
            <>
              {' '}
              y tu sesion tiene rol <span className="font-mono text-[13px] text-ink">{ROLE_LABELS[actual]}</span>
            </>
          ) : null}
          . Si necesitas acceso, solicita al administrador de la plataforma.
        </p>
        <div className="mt-5 flex flex-wrap gap-2">
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
