import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Panel } from '../ui'
import { LEGAL_CONTACT_EMAIL, LEGAL_DOCUMENTS, LEGAL_EFFECTIVE_DATE, LEGAL_VERSION } from '../../config/legal'

/**
 * Shared shell for the five public legal documents.
 *
 * It renders the version and effective date from `config/legal.ts`, which the
 * backend records on every acceptance: the reader can see exactly which text
 * they are agreeing to, and the acceptance row points at the same value.
 */
export function LegalPage({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle: string
  children: ReactNode
}) {
  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <Panel>
        <p className="label-caps">Documentos legales</p>
        <h1 className="mt-2 text-2xl font-semibold text-ink">{title}</h1>
        <p className="mt-2 text-sm text-ink-secondary">{subtitle}</p>

        <dl className="mt-4 flex flex-wrap gap-x-6 gap-y-1 border-t border-hairline-subtle pt-3 font-mono text-xs text-ink-muted">
          <div>
            <dt className="inline">Version vigente: </dt>
            <dd className="inline text-ink-secondary" data-testid="legal-version">
              {LEGAL_VERSION}
            </dd>
          </div>
          <div>
            <dt className="inline">En vigor desde: </dt>
            <dd className="inline text-ink-secondary">{LEGAL_EFFECTIVE_DATE}</dd>
          </div>
          <div>
            <dt className="inline">Contacto: </dt>
            <dd className="inline">
              <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
                {LEGAL_CONTACT_EMAIL}
              </a>
            </dd>
          </div>
        </dl>

        <div className="mt-5 space-y-5 text-sm leading-relaxed text-ink-secondary">{children}</div>
      </Panel>

      <aside
        aria-label="Advertencia sobre el alcance de este documento"
        className="rounded-md border border-hairline bg-surface-inset/70 p-4 text-xs leading-relaxed text-ink-muted"
      >
        Este documento describe como opera el servicio y como se tratan los datos
        personales de quien lo usa. Se redacto para este proyecto y para el marco
        colombiano aplicable, pero <strong className="text-ink-secondary">no constituye
        asesoria juridica</strong>: debe ser revisado por un abogado colombiano antes de
        operar con usuarios reales y, si el servicio se ofrece fuera de Colombia, por
        asesores de cada jurisdiccion que resulte aplicable.
      </aside>

      <nav aria-label="Otros documentos legales">
        <ul className="flex flex-wrap gap-x-4 gap-y-2 text-xs">
          {LEGAL_DOCUMENTS.map((document) => (
            <li key={document.key}>
              <Link to={document.path} className="link-accent">
                {document.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  )
}

/** Section heading of a legal document. */
export function LegalSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="text-base font-semibold text-ink">{title}</h2>
      {children}
    </section>
  )
}

/** Bullet list inside a legal document. */
export function LegalList({ items }: { items: ReactNode[] }) {
  return (
    <ul className="list-disc space-y-1.5 pl-5 marker:text-accent-cyan">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  )
}
