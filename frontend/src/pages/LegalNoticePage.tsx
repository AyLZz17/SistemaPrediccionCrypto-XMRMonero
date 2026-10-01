import { LegalList, LegalPage, LegalSection } from '../components/legal/LegalPage'
import { LEGAL_CONTACT_EMAIL, dataRequestHref } from '../config/legal'

/**
 * Aviso legal del analisis predictivo + canal de contacto + ejercicio de
 * derechos sobre los datos (solicitud de eliminacion o actualizacion).
 *
 * El texto del aviso es el que R-11 exige visible en la UI, la documentacion de
 * la API, el README y los reportes exportados.
 */
export default function LegalNoticePage() {
  return (
    <LegalPage
      title="Aviso legal y contacto"
      subtitle="Alcance del analisis predictivo, canales de contacto y ejercicio de derechos sobre datos personales."
    >
      <LegalSection title="1. Aviso legal de analisis predictivo">
        <p className="rounded-md border border-accent-red/40 bg-accent-red/5 p-3 text-ink-secondary">
          XMR-Forecast analiza y estima el precio de cierre y la direccion de Monero (XMR) usando
          modelos evaluados de forma rigorosa. <strong className="text-ink">No ofrece asesoria
          financiera, no promete rentabilidad y no simula operaciones ni backtesting de trading.</strong>{' '}
          Toda cifra publicada es capacidad predictiva evaluada sobre datos historicos, no una
          recomendacion de compra o venta. Invierta bajo su propio riesgo.
        </p>
        <p>
          Las comparaciones entre modelos solo son validas cuando proceden del mismo conjunto de
          prueba y de las mismas fechas, y siempre se publican junto a los modelos base y a la
          medida de incertidumbre.
        </p>
      </LegalSection>

      <LegalSection title="2. Responsable del servicio">
        <p>
          AyLZz Software Solutions (AyLZz17). Aplicacion web de analisis predictivo de series de
          tiempo sobre Monero (XMR).
        </p>
      </LegalSection>

      <LegalSection title="3. Contacto">
        <p>
          Correo general, soporte y documentacion:{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          . Indique el correo de su cuenta y el motivo para agilizar la respuesta.
        </p>
      </LegalSection>

      <LegalSection title="4. Solicitud de eliminacion o actualizacion de datos">
        <p>
          Para pedir la <strong className="text-ink">eliminacion</strong> de su cuenta y de sus
          datos personales, o la <strong className="text-ink">actualizacion o rectificacion</strong>{' '}
          de los mismos, envie un correo con el asunto{' '}
          <strong className="text-ink">&ldquo;Derechos de titular de datos&rdquo;</strong> e indique:
        </p>
        <LegalList
          items={[
            'El correo electronico registrado en la cuenta que desea gestionar.',
            'El derecho que ejerce: acceso, rectificacion, supresion, revocatoria de autorizacion o portabilidad.',
            'Cualquier dato necesario para verificar su identidad, sin enviar contrasenas ni documentos que no se soliciten.',
          ]}
        />
        <p>
          Responderemos en un plazo razonable y dentro del que fije la normativa aplicable. La
          eliminacion de la cuenta no borrara los registros que deban conservarse por obligacion
          legal o para atender reclamos; se informara exactamente que se conserva y por que.
        </p>
        <p>
          <a
            href={dataRequestHref('Derechos de titular de datos')}
            className="inline-block"
            data-testid="data-request-link"
          >
            Solicitar eliminacion o actualizacion de mis datos
          </a>
        </p>
      </LegalSection>

      <LegalSection title="5. Reclamos">
        <p>
          Si la respuesta no le satisface, puede presentar un reclamo ante la Superintendencia de
          Industria y Comercio de Colombia o ante la autoridad de proteccion de datos de su pais
          de residencia, sin perjuicio de las vias judiciales que correspondan.
        </p>
      </LegalSection>

      <LegalSection title="6. Version">
        <p>
          Version <strong className="text-ink">2026-10-01</strong>, en vigor desde el 1 de octubre
          de 2026.
        </p>
      </LegalSection>
    </LegalPage>
  )
}
