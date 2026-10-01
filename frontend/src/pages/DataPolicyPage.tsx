import { LegalList, LegalPage, LegalSection } from '../components/legal/LegalPage'
import { LEGAL_CONTACT_EMAIL } from '../config/legal'

/**
 * Politica de tratamiento de datos personales.
 *
 * Marco de referencia: Ley 1581 de 2012, Decreto 1074 de 2015 y las normas que
 * correspondan segun el tratamiento real; para usuarios fuera de Colombia se
 * documenta tambien la posible aplicacion de GDPR, UK GDPR o CCPA/CPRA.
 *
 * Todo lo que aqui se describe coincide con lo que el sistema hace hoy: no se
 * declara una finalidad que no exista en el codigo.
 */
export default function DataPolicyPage() {
  return (
    <LegalPage
      title="Politica de tratamiento de datos personales"
      subtitle="Como se recolectan, usan, conservan y protegen los datos personales de quienes usan XMR-Forecast."
    >
      <LegalSection title="1. Responsable del tratamiento">
        <p>
          <strong className="text-ink">AyLZz Software Solutions</strong> es responsable del
          tratamiento de los datos personales recolectados a traves de XMR-Forecast. Canal de
          atencion:{' '}
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`} className="link-accent">
            {LEGAL_CONTACT_EMAIL}
          </a>
          .
        </p>
      </LegalSection>

      <LegalSection title="2. Datos que se recolectan">
        <p>
          <strong className="text-ink">Datos de registro.</strong> Correo electronico, nombre
          completo y el hash de la contrasena (la contrasena en claro nunca se almacena).
        </p>
        <p>
          <strong className="text-ink">Datos de autenticacion.</strong> Estado de la cuenta,
          fecha de verificacion del correo, fecha del ultimo inicio de sesion, intentos fallidos
          y bloqueos temporales, y los tokens de sesion guardados solo como hash.
        </p>
        <p>
          <strong className="text-ink">Datos recibidos de Google OAuth.</strong> El identificador
          permanente de la cuenta de Google, el correo electronico, el nombre visible y la
          afirmacion de que ese correo esta verificado. No se solicita ni se almacena ningun otro
          dato del perfil de Google, y no se recibe informacion de otros servicios de ese proveedor.
        </p>
        <p>
          <strong className="text-ink">Datos tecnicos.</strong> Direccion IP, agente de usuario y
          identificadores de correlacion de cada peticion, necesarios para la seguridad, el rate
          limiting y la diagnostica de incidentes.
        </p>
        <p>
          <strong className="text-ink">Datos de uso.</strong> Navegacion dentro de la aplicacion:
          consultas de mercado, predicciones solicitadas, experimentos y metricas consultadas.
        </p>
        <p>
          <strong className="text-ink">Datos de auditoria.</strong> Registro de acciones
          sensibles (inicio y cierre de sesion, cambios de contrasena, altas y permisos de
          administracion, accesos a recursos) con actor, fecha, resultado y motivo.
        </p>
        <p>
          <strong className="text-ink">Datos de consentimiento.</strong> Version del documento
          aceptado, fecha y hora de la aceptacion, cuenta, canal y direccion IP desde el que se
          acepto.
        </p>
      </LegalSection>

      <LegalSection title="3. Finalidades del tratamiento">
        <LegalList
          items={[
            'Crear y administrar la cuenta, autenticar al usuario y mantener la sesion.',
            'Confirmar el correo electronico y enviar avisos de seguridad de la cuenta.',
            'Prestar el servicio: datos de mercado, predicciones, experimentos, metricas y reportes.',
            'Prevenir fraude, fuerza bruta, abuso del servicio y otros incidentes de seguridad.',
            'Cumplir obligaciones legales y atender requerimientos de autoridad competente.',
            'Enviar comunicaciones comerciales SOLO cuando fueron autorizadas expresamente; en ningun caso son condicion para usar el servicio.',
          ]}
        />
        <p>
          Los datos de mercado se tratan de forma agregada y no identifican a ninguna persona.
        </p>
      </LegalSection>

      <LegalSection title="4. Base legal o autorizacion aplicable">
        <p>
          El tratamiento se apoya en la <strong className="text-ink">autorizacion expresa e
          informada</strong> del titular, recolectada en el momento del registro mediante dos
          aceptaciones independientes (terminos y condiciones; esta politica) y registrada con su
          version, fecha, hora, cuenta e IP, tal como lo exige el articulo 8 de la Ley 1581 de
          2012.
        </p>
        <p>
          Quien se registra con Google acepta estos documentos <strong className="text-ink">antes</strong> de
          que se cree la cuenta: sin esa aceptacion registrada, no se crea ninguna cuenta por ese
          camino. Ademas, el tratamiento es necesario para ejecutar el contrato solicitado por el
          usuario (prestacion del servicio y gestion de la cuenta) y para atender obligaciones
          legales y de seguridad.
        </p>
        <p>
          La aceptacion de comunicaciones comerciales es <strong className="text-ink">opcional</strong> y
          puede negarse o revocarse en cualquier momento sin que eso afecte el acceso al servicio.
        </p>
      </LegalSection>

      <LegalSection title="5. Tiempo de conservacion">
        <LegalList
          items={[
            'Cuenta y datos asociados: mientras la cuenta este activa y, despues, el minimo necesario para obligaciones legales.',
            'Tokens de verificacion de correo: 48 horas, de un solo uso.',
            'Tokens de recuperacion de contrasena: 2 horas, de un solo uso.',
            'Sesiones (refresh tokens): 30 dias como maximo, revocables en cualquier momento.',
            'Registros de acceso y auditoria: periodo limitado de operacion y seguridad, despues se eliminan o anonimizan.',
            'Datos de consentimiento: durante el plazo que permita demostrar la aceptacion ante una autoridad.',
          ]}
        />
      </LegalSection>

      <LegalSection title="6. Encargados y proveedores">
        <p>
          El servicio se apoya en proveedores que tratan datos por cuenta de AyLZz Software
          Solutions y segun sus instrucciones:
        </p>
        <LegalList
          items={[
            'Servicio de correo electronico (envio de confirmaciones y recuperacion) — para envio directo se usa la API de Gmail por HTTPS.',
            'Google OAuth 2.0 / OpenID Connect — verificacion de identidad; opera con sus propios terminos y politicas.',
            'PostgreSQL — base de datos relacional donde residen las cuentas y los registros.',
            'Redis — cache, limites de peticiones y control de sesiones; no almacena perfiles.',
            'MLflow — seguimiento de experimentos de modelos, sin datos de identificacion personal.',
            'Proveedor de infraestructura (alojamiento, registros y monitoreo) — operacion y seguridad del servicio.',
          ]}
        />
        <p>
          Cada uno de ellos actua como encargado o como responsable propio segun el caso, y solo
          recibe los datos necesarios para su funcion.
        </p>
      </LegalSection>

      <LegalSection title="7. Transferencias internacionales">
        <p>
          Los proveedores indicados pueden operar fuera de Colombia, por lo que los datos pueden
          ser tratados en otro pais (por ejemplo, Estados Unidos o la Union Europea). En esos
          casos se aplican las medidas exigidas por la normativa colombiana y, cuando resulte
          aplicable, por el GDPR o el UK GDPR: proveedores con compromisos de seguridad, cifrado
          en transito y, segun corresponda, clausulas contractuales tipo u otros mecanismos
          reconocidos.
        </p>
      </LegalSection>

      <LegalSection title="8. Medidas de seguridad">
        <LegalList
          items={[
            'Cifrado en transito con TLS en todos los entornos y cabeceras HSTS.',
            'Contrasenas guardadas con BCrypt (coste 12) y tokens opacos guardados solo como hash HMAC-SHA256.',
            'Sesiones cortas, rotacion de refresh tokens con deteccion de reutilizacion y revocacion de la familia.',
            'Autenticacion y autorizacion por rol en cada recurso, y limites de peticiones para frenar fuerza bruta.',
            'Secretos unicamente en variables de entorno, fuera del repositorio y de los registros.',
            'Auditoria de acciones sensibles y revision periodica de dependencias e imagenes.',
            'Minimo privilegio, separacion de entornos y acceso administrativo protegido.',
          ]}
        />
      </LegalSection>

      <LegalSection title="9. Derechos de los titulares">
        <p>
          El titular puede ejercer en cualquier momento los derechos de{' '}
          <strong className="text-ink">consulta, actualizacion y rectificacion</strong> (a traves
          del panel de cuenta o por correo), <strong className="text-ink">supresion</strong>{' '}
          (eliminacion de la cuenta y de los datos que no deban conservarse por obligacion legal),{' '}
          <strong className="text-ink">revocatoria</strong> de la autorizacion otorgada (incluida
          la de comunicaciones comerciales) y <strong className="text-ink">portabilidad</strong>{' '}
          cuando la normativa aplicable la reconozca.
        </p>
        <p>
          La revocatoria no afecta la licitud del tratamiento realizado con anterioridad ni el
          uso que el servicio haya de hacer de los datos para cumplir una obligacion legal.
        </p>
      </LegalSection>

      <LegalSection title="10. Canal de atencion y procedimiento de reclamos">
        <LegalList
          items={[
            'Escribir a ' + LEGAL_CONTACT_EMAIL + ' con el asunto "Derechos de titular de datos" e indicar el correo de la cuenta.',
            'Se responde en un plazo razonable y, en todo caso, dentro de los plazos que fije la normativa aplicable.',
            'Si la respuesta no es satisfactoria, el titular puede acudir a la Superintendencia de Industria y Comercio de Colombia o a la autoridad de proteccion de datos de su pais.',
          ]}
        />
      </LegalSection>

      <LegalSection title="11. Tratamiento de menores">
        <p>
          El servicio esta orientado a mayores de edad. No se recolecta conscientemente datos de
          menores de edad. Si se detecta una cuenta creada por un menor sin la autorizacion
          requerida, se elimina al recibirse la solicitud en el canal de atencion.
        </p>
      </LegalSection>

      <LegalSection title="12. Uso de cookies y tecnologias similares">
        <p>
          El servicio usa una cookie tecnica estrictamente necesaria (<code>xmr_refresh</code>,
          HttpOnly y SameSite=Strict) para renovar la sesion, y almacenamiento local del navegador
          para conservar la sesion mientras el usuario no la cierre. No se usan cookies de
          publicidad ni de perfilado de terceros. Detalle completo en la{' '}
          <a href="/cookies" className="link-accent">
            politica de cookies
          </a>
          .
        </p>
      </LegalSection>

      <LegalSection title="13. Consentimiento y version">
        <p>
          Esta politica es la version <strong className="text-ink">2026-10-01</strong>, en vigor
          desde el 1 de octubre de 2026. Cada aceptacion se registra con la version, la fecha y
          hora, la cuenta y el canal desde el que se acepto, de modo que puede demostrarse que
          texto fue el aceptado en cada momento. Las politicas nuevas no se consideran aceptadas
          por cuentas antiguas: cuando el tratamiento lo exija, se pedira una aceptacion expresa
          nueva.
        </p>
      </LegalSection>

      <LegalSection title="14. Normativa de otros paises">
        <p>
          Para usuarios de la Union Europea o del Reino Unido puede aplicar el GDPR o el UK GDPR
          (derechos de acceso, rectificacion, supresion, oposicion, limitacion y portabilidad, y
          base juridica especifica). Para residentes de California puede aplicar el CCPA/CPRA
          (derecho a saber, a eliminar y a no ser discriminado). Se atienden solicitudes formuladas
          por cualquiera de esos canales, en los terminos que corresponda a la normativa del
          solicitante.
        </p>
      </LegalSection>
    </LegalPage>
  )
}
