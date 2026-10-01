import { LegalList, LegalPage, LegalSection } from '../components/legal/LegalPage'
import { LEGAL_CONTACT_EMAIL } from '../config/legal'

/**
 * Terminos y condiciones de uso de XMR-Forecast.
 *
 * Cobertura exigida por el proyecto: responsable, descripcion del servicio,
 * naturaleza experimental, aviso de que no es asesoria financiera, riesgos,
 * limitaciones, responsabilidad del usuario, usos permitido y prohibido,
 * propiedad intelectual, limitacion de responsabilidad, suspension,
 * modificaciones, ley aplicable, jurisdiccion, contacto y fecha de vigor.
 */
export default function TermsPage() {
  return (
    <LegalPage
      title="Terminos y condiciones"
      subtitle="Condiciones de uso del servicio XMR-Forecast, prestado por AyLZz Software Solutions."
    >
      <LegalSection title="1. Identificacion del responsable del servicio">
        <p>
          El servicio XMR-Forecast es operado por <strong className="text-ink">AyLZz Software
          Solutions</strong> (denominacion de trabajo: AyLZz17), con canal de atencion en{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          . El servicio se presta exclusivamente por via electronica, a traves de su aplicacion
          web. Cualquier comunicacion, solicitud o reclamo se dirige a ese correo, indicando el
          motivo y los datos de contacto del remitente.
        </p>
      </LegalSection>

      <LegalSection title="2. Descripcion del servicio">
        <p>
          XMR-Forecast es una aplicacion web de analisis predictivo de series de tiempo aplicado
          al activo <strong className="text-ink">Monero (XMR)</strong>. Estima el precio de cierre
          del dia siguiente y la direccion probable de movimiento (alza o baja), y presenta los
          resultados de modelos recurrentes (LSTM y GRU) comparados frente a modelos base
          (media movil, regresion lineal y ARIMA), con metricas de error publicadas.
        </p>
        <p>
          La aplicacion incluye acceso a datos historicos de mercado, paneles de prediccion,
          experimentos, metricas y administracion, sujeto al rol asignado a cada cuenta.
        </p>
      </LegalSection>

      <LegalSection title="3. Naturaleza experimental y analitica">
        <p>
          El sistema es una herramienta <strong className="text-ink">experimental y de
          investigacion</strong>. Sus salidas son estimaciones estadisticas calculadas sobre datos
          historicos y sobre la capacidad predictiva evaluada en pruebas controladas. El servicio
          no ejecuta operaciones, no gestiona carteras, no simula carteras de inversion y no
          realiza backtesting de estrategias de trading.
        </p>
      </LegalSection>

      <LegalSection title="4. No es asesoria financiera">
        <p className="rounded-md border border-accent-red/40 bg-accent-red/5 p-3 text-ink-secondary">
          <strong className="text-ink">Ninguna salida del sistema constituye asesoria
          financiera, recomendacion de compra o venta, ni promesa de rentabilidad.</strong> El
          sistema no predice el mercado: informa su capacidad predictiva evaluada. Toda decision
          tomada a partir de la informacion aqui presentada es responsabilidad exclusiva de quien
          la toma.
        </p>
      </LegalSection>

      <LegalSection title="5. Riesgos de los mercados de criptomonedas">
        <p>El usuario reconoce que los activos digitales presentan, entre otros, estos riesgos:</p>
        <LegalList
          items={[
            'Volatilidad extrema y movimientos de precio que ningún modelo historico puede anticipar.',
            'Riesgo de liquidez, de contraparte y de fallo de las plataformas donde se opere.',
            'Cambios regulatorios que pueden suspender o prohibir operaciones en determinados paises.',
            'Riesgo tecnico: errores de datos, interrupciones del servicio y perdida de acceso.',
          ]}
        />
      </LegalSection>

      <LegalSection title="6. Limitaciones de disponibilidad y precision">
        <p>
          El servicio se presta &ldquo;tal cual&rdquo;. Los datos provienen de fuentes de terceros que pueden
          llegar incompletos, atrasados o con errores; los modelos se recalculan periodicamente y
          sus metricas cambian con cada conjunto de datos. No se garantiza la disponibilidad
          continua, ni la ausencia de errores, ni que cualquier estimacion sea correcta. Las
          interrupciones por mantenimiento, fallas del proveedor o causas de fuerza mayor no
          generan derecho a compensacion.
        </p>
      </LegalSection>

      <LegalSection title="7. Responsabilidad del usuario">
        <p>El usuario se compromete a:</p>
        <LegalList
          items={[
            'Proporcionar datos de registro veraces y mantenerlos actualizados.',
            'Custodiar sus credenciales: no compartirlas ni permitir el uso de su cuenta por terceros.',
            'Usar las salidas del sistema como insumo informativo, no como una orden de inversion.',
            'Revisar de forma independiente cualquier informacion antes de tomar una decision.',
          ]}
        />
      </LegalSection>

      <LegalSection title="8. Uso permitido">
        <p>
          Se permite el uso personal e interno del servicio para analisis, docencia,
          investigacion y evaluacion de la capacidad predictiva de los modelos, dentro de los
          limites del rol asignado a la cuenta y de la legislacion aplicable.
        </p>
      </LegalSection>

      <LegalSection title="9. Uso prohibido">
        <LegalList
          items={[
            'Presentar las salidas del sistema como asesororia, recomendacion o garantia de rentabilidad.',
            'Revender, revender o redistribuir masivamente los datos o las metricas publicadas.',
            'Intentar vulnerar la seguridad, escal privilegios, realizar enjuagues de peticiones o denegacion de servicio.',
            'Usar la plataforma para actividades ilicitas o que infrinjan derechos de terceros.',
            'Suplantar identidades o crear cuentas automatizadas para manipular mediciones.',
          ]}
        />
      </LegalSection>

      <LegalSection title="10. Propiedad intelectual">
        <p>
          El codigo fuente, la interfaz, la identidad visual, la documentacion, los modelos
          entrenados, las configuraciones de experimento y las metricas derivadas pertenecen a
          AyLZz Software Solutions o a sus licenciantes. Los datos de mercado crudos conservan
          la licencia de su fuente original. No se concede ninguna licencia salvo la de uso del
          servicio conforme a estos terminos.
        </p>
      </LegalSection>

      <LegalSection title="11. Uso de contenido, modelos y metricas">
        <p>
          Las metricas y los resultados pueden citarse indicando la fuente y la fecha de la
          corrida. No pueden alterarse, presentarse fuera de su contexto (sin los modelos base ni
          la medida de incertidumbre) ni usarse para promocionar productos financieros. Las
          comparaciones solo son validas cuando proceden del mismo conjunto de prueba y de las
          mismas fechas.
        </p>
      </LegalSection>

      <LegalSection title="12. Limitacion de responsabilidad">
        <p>
          En la maxima medida permitida por la ley aplicable, AyLZz Software Solutions no responde
          por daños indirectos, lucro cesante, perdida de inversion, perdida de datos o decisiones
          tomadas a partir de las salidas del sistema. Su responsabilidad total se limita al valor
          efectivamente pagado por el servicio en los doce meses anteriores al hecho, o, si el
          servicio es gratuito, a la suma simbolica que la ley permita exigir. Nada de esto excluye
          la responsabilidad que la normativa colombiana de proteccion al consumidor no permita
          excluir.
        </p>
      </LegalSection>

      <LegalSection title="13. Suspension y cancelacion de cuentas">
        <p>
          El servicio puede suspender o dar de baja una cuenta cuando se detecte uso prohibido,
          incumplimiento de estos terminos, riesgo de seguridad o requerimiento de autoridad
          competente. El usuario puede cerrar su cuenta en cualquier momento desde el panel de
          cuenta o escribiendo al canal de contacto. La suspension no genera derecho a reembolso
          cuando el servicio es gratuito.
        </p>
      </LegalSection>

      <LegalSection title="14. Modificaciones de los terminos">
        <p>
          Estos terminos pueden actualizarse. La version vigente se publica en esta misma pagina,
          con su fecha de entrada en vigor, y se informa a los usuarios por la aplicacion o por
          correo. El uso continuado tras la entrada en vigor implica su aceptacion; cuando la ley
          lo exija, se solicitara una aceptacion expresa y nueva, que quedara registrada con su
          version, fecha y hora.
        </p>
      </LegalSection>

      <LegalSection title="15. Ley aplicable y jurisdiccion">
        <p>
          Estos terminos se rigen por la legislacion de la Republica de Colombia, en particular
          las normas aplicables a contratos de consumo, comercio electronico y proteccion de datos
          personales (Ley 1581 de 2012 y Decreto 1074 de 2015). Para cualquier controversia son
          competentes los tribunales del lugar de residencia del consumidor, sin perjuicio de las
          normas imperativas que protejan al usuario en su jurisdiccion.
        </p>
        <p>
          Si el usuario reside fuera de Colombia, puede resultarle aplicable ademas la normativa
          de su pais (por ejemplo GDPR o UK GDPR en Europa, o CCPA/CPRA en California). En cuanto
          sea compatible con la ley imperativa local, se aplicara la legislacion colombiana.
        </p>
      </LegalSection>

      <LegalSection title="16. Mecanismos de contacto">
        <p>
          Preguntas, solicitudes o reclamos:{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          . Para solicitudes sobre datos personales (acceso, rectificacion, supresion o
          revocatoria) use el asunto &ldquo;Derechos de titular de datos&rdquo;, o el enlace de solicitud de
          eliminacion del pie de pagina.
        </p>
      </LegalSection>

      <LegalSection title="17. Fecha de entrada en vigor">
        <p>
          Version 2026-10-01, en vigor desde el 1 de octubre de 2026. La aceptacion de esta
          version queda registrada con la fecha y hora de la aceptacion y con la identificacion de
          la cuenta que la realizo.
        </p>
      </LegalSection>
    </LegalPage>
  )
}
