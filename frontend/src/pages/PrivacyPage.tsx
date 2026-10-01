import { LegalList, LegalPage, LegalSection } from '../components/legal/LegalPage'
import { LEGAL_CONTACT_EMAIL } from '../config/legal'

/**
 * Politica de privacidad: confidencialidad, seguridad y transparencia.
 *
 * El detalle del tratamiento (que datos, para que, durante cuanto tiempo y con
 * que proveedores) vive en la Politica de tratamiento de datos personales; aqui
 * se describe la postura general de privacidad del servicio.
 */
export default function PrivacyPage() {
  return (
    <LegalPage
      title="Politica de privacidad"
      subtitle="Compromiso de confidencialidad, seguridad y transparencia de XMR-Forecast."
    >
      <LegalSection title="1. Principio de minimizacion">
        <p>
          Solo se recoge lo necesario para prestar el servicio y protegerlo: nada de la vida
          privada del usuario, nada de contactos, nada del comportamiento fuera de la aplicacion.
          No se compran datos personales, no se venden y no se comparten con fines publicitarios.
        </p>
      </LegalSection>

      <LegalSection title="2. Quien ve los datos">
        <p>
          El equipo operativo de AyLZz Software Solutions accede a los datos unicamente cuando es
          necesario para operar o asegurar el servicio, bajo registro. Los proveedores indicados
          en la politica de tratamiento actuan bajo instruccion y con las medidas descritas alli.
        </p>
      </LegalSection>

      <LegalSection title="3. Seguridad">
        <LegalList
          items={[
            'Cifrado en transito (TLS) en todos los entornos.',
            'Contrasenas con hash BCrypt; nunca en claro, ni en registros, ni en respaldos legibles.',
            'Sesiones cortas con rotacion de tokens y revocacion ante reutilizacion.',
            'Control de acceso por rol y limites de peticiones contra fuerza bruta.',
            'Secretos fuera del codigo, en variables de entorno, con rotacion ante compromiso.',
          ]}
        />
        <p>
          Ningun sistema es infalible: ante un incidente que afecte datos personales se sigue el
          runbook de respuesta del proyecto, se preserva la evidencia, se rotan credenciales
          comprometidas y se informa a los titulares y a la autoridad cuando corresponda.
        </p>
      </LegalSection>

      <LegalSection title="4. Transparencia">
        <p>
          Esta politica, la de tratamiento de datos y la de cookies estan publicadas y enlazadas
          en el pie de pagina de todas las pantallas. La version vigente y su fecha estan visibles
          en cada documento, y cada aceptacion queda registrada con la version que se acepto.
        </p>
      </LegalSection>

      <LegalSection title="5. Menores">
        <p>
          El servicio no esta dirigido a menores de edad y no recoge conscientemente sus datos.
          Ante una solicitud fundada se elimina la cuenta correspondiente.
        </p>
      </LegalSection>

      <LegalSection title="6. Cambios y contacto">
        <p>
          Cualquier cambio se publica con su nueva version y fecha. Para preguntas sobre
          privacidad:{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          . Version vigente: <strong className="text-ink">2026-10-01</strong>, en vigor desde el
          1 de octubre de 2026.
        </p>
      </LegalSection>
    </LegalPage>
  )
}
