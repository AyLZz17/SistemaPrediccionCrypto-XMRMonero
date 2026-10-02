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
    <div className="mx-auto w-full max-w-3xl">
      <p className="font-mono text-[11px] uppercase tracking-wide text-ink-muted">Documento legal</p>
      <h1 className="mt-1.5 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1>
      <p className="mt-2 max-w-2xl text-sm leading-normal text-ink-secondary">{subtitle}</p>

      <dl className="mt-5 grid gap-px border border-hairline-subtle bg-hairline-subtle sm:grid-cols-3">
        <div className="bg-deep px-4 py-3">
          <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">Versión vigente</dt>
          <dd className="mt-1 font-mono text-[13px] text-ink" data-testid="legal-version">
            {LEGAL_VERSION}
          </dd>
        </div>
        <div className="bg-deep px-4 py-3">
          <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">En vigor desde</dt>
          <dd className="mt-1 font-mono text-[13px] text-ink">{LEGAL_EFFECTIVE_DATE}</dd>
        </div>
        <div className="bg-deep px-4 py-3">
          <dt className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">Contacto</dt>
          <dd className="mt-1 font-mono text-[13px]">
            <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
              {LEGAL_CONTACT_EMAIL}
            </a>
          </dd>
        </div>
      </dl>

      <Panel className="mt-5">
        <div className="space-y-5 text-sm leading-relaxed text-ink-secondary">{children}</div>
      </Panel>

      <aside
        aria-label="Advertencia sobre el alcance de este documento"
        className="mt-5 rounded-sm border border-hairline-default bg-surface-1"
      >
        <p className="border-b border-hairline-subtle px-4 py-2 font-mono text-[10px] uppercase tracking-wide text-ink-muted">
          Alcance del documento
        </p>
        <p className="px-4 py-3 text-xs leading-relaxed text-ink-muted">
          Este documento describe como opera el servicio y como se tratan los datos
          personales de quien lo usa. Se redacto para este proyecto y para el marco
          colombiano aplicable, pero <strong className="text-ink-secondary">no constituye
          asesoria juridica</strong>: debe ser revisado por un abogado colombiano antes de
          operar con usuarios reales y, si el servicio se ofrece fuera de Colombia, por
          asesores de cada jurisdiccion que resulte aplicable.
        </p>
      </aside>

      <nav aria-label="Otros documentos legales" className="mt-5 border-t-2 border-hairline-strong pt-3">
        <ul className="flex flex-wrap gap-x-5 gap-y-2 font-mono text-[11px] uppercase tracking-wide">
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
    <section className="space-y-2 border-t border-hairline-subtle pt-4 first:border-t-0 first:pt-0">
      <h2 className="font-mono text-[13px] font-semibold uppercase tracking-wide text-ink">{title}</h2>
      {children}
    </section>
  )
}

/** Bullet list inside a legal document. */
export function LegalList({ items }: { items: ReactNode[] }) {
  return (
    <ul className="list-[square] space-y-1.5 pl-5 marker:text-ink-muted">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  )
}
