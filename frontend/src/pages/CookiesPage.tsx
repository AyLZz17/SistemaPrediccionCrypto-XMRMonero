import { LegalList, LegalPage, LegalSection } from '../components/legal/LegalPage'
import { LEGAL_CONTACT_EMAIL } from '../config/legal'

/**
 * Politica de cookies.
 *
 * Describe exactamente lo que la aplicacion usa hoy: una cookie tecnica de
 * sesion y almacenamiento local. No hay cookies de publicidad ni de perfilado
 * de terceros, y por eso no hay banner de consentimiento de cookies: no hay nada
 * que aceptar por encima de lo estrictamente necesario para prestar el servicio.
 */
export default function CookiesPage() {
  return (
    <LegalPage
      title="Politica de cookies"
      subtitle="Que tecnicas de almacenamiento usa el navegador en XMR-Forecast y para que."
    >
      <LegalSection title="1. Que es una cookie">
        <p>
          Una cookie es un pequeno archivo que un sitio guarda en el navegador. Algunas son
          imprescindibles para que la sesion funcione; otras sirven para medir o publicar. Aqui
          solo se usan las primeras.
        </p>
      </LegalSection>

      <LegalSection title="2. Cookies y almacenamiento que se usan">
        <LegalList
          items={[
            <>
              <code>xmr_refresh</code> — cookie tecnica, <strong className="text-ink">HttpOnly</strong>,{' '}
              <strong className="text-ink">Secure</strong> y <strong className="text-ink">SameSite=Strict</strong>,
              que contiene el token de renovacion de sesion. Dura lo que la sesion (hasta 30 dias)
              y se borra al cerrar sesion. Sin ella no hay forma de renovar la sesion sin exponer
              el token a JavaScript.
            </>,
            <>
              Almacenamiento local del navegador — guarda la sesion activa (rol y caducidad) para
              que la aplicacion no pida autenticacion en cada recarga. Se borra al cerrar sesion o
              al limpiar los datos del sitio.
            </>,
          ]}
        />
      </LegalSection>

      <LegalSection title="3. Que NO se usa">
        <LegalList
          items={[
            'Cookies de publicidad o de perfilamiento.',
            'Herramientas de analitica de terceros (Google Analytics u otras) integradas en la aplicacion.',
            'Píxeles de redes sociales o de seguimiento entre sitios.',
            'Cookies de personalizacion no esenciales.',
          ]}
        />
        <p>
          Como no se instala ninguna cookie no esencial, no se solicita un consentimiento de
          cookies adicionales. Si en el futuro se añadiera alguna, se pedira antes de instalarla y
          esta politica se actualizara con su nueva version.
        </p>
      </LegalSection>

      <LegalSection title="4. Como controlarlas desde el navegador">
        <p>
          Cualquier navegador permite bloquear o eliminar cookies desde su configuracion de
          privacidad. Bloquear <code>xmr_refresh</code> impide renovar la sesion: la aplicacion
          pedira iniciar sesion de nuevo, sin perder datos de la cuenta.
        </p>
      </LegalSection>

      <LegalSection title="5. Version y contacto">
        <p>
          Version <strong className="text-ink">2026-10-01</strong>, en vigor desde el 1 de octubre
          de 2026. Dudas sobre cookies o privacidad:{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          .
        </p>
      </LegalSection>
    </LegalPage>
  )
}
