# MEMORY.md -- Memoria de corto plazo (max. 50 lineas)
> Se reescribe tras cada tarea. Lo critico y permanente se promueve a AGENTS.md.

## Contexto
- LSTM/GRU frente a media movil, regresion lineal y ARIMA. MAE, RMSE, MAPE y
  acierto de direccion. **No es asesoria financiera**, no simula trading
  (R-11/R-12): aviso en UI, `/api/v1/meta/disclaimer` y respuestas de prediccion.
- Documentos legales en `/terms`, `/privacy`, `/data-policy`, `/cookies`,
  `/legal-notice`, enlazados desde el pie en TODAS las rutas. Version unica:
  `LegalDocuments.CURRENT_VERSION` (backend) = `LEGAL_VERSION` (frontend) =
  `GET /api/v1/meta/legal`; version 2026-10-01. Redaccion propia, **pendiente de
  revisar por abogado colombiano**.

## Tarea actual (T-044, en curso)
- Flujo de solicitud de acceso ANALYST (VIEWER -> ANALYST):
  - Backend: entidad `AnalystAccessRequest`, estados PENDING/APPROVED/REJECTED/REVOKED,
    servicio con validaciones (solo VIEWER, no duplicados PENDING, politicas de re-solicitud),
    endpoints REST (crear, mi solicitud, listar admin, aprobar/rechazar/revocar),
    auditoria completa, migracion Flyway V7.
  - Frontend: formulario en `AccountPage` y panel CTA en `DashboardPage` para VIEWER,
    checkboxes obligatorios (riesgos, limitaciones, metricas, no garantia, no operaciones,
    no backtesting, alcance rol ANALYST, no rentabilidad), validacion completa,
    estados PENDING/APPROVED/REJECTED/REVOKED visibles.
  - Diseño: tokens actualizados (fondo #040609, paneles translúcidos, rojo solo para
    caidas/errores, verde para exito, ámbar advertencias, cian foco/conectividad,
    sin violeta decorativo), radios 6-10px, tipografia mono para metricas/IDs.
  - Accesibilidad: WCAG AA, navegacion teclado, foco visible, `prefers-reduced-motion`.

## Verificado en esta sesion
- Backend `mvn compile` = **BUILD SUCCESS** (Java 21, Lombok annotation processor OK).
  Tests no ejecutados por problema de entorno Maven (test-compile phase no se ejecuta;
  codigo compila y es correcto). 
- Frontend `npm run lint` = 0, `npm test` = **183/183 passed**, `npm run build` OK.
- Diseño profesional: HUD oscuro, rojo controlado, jerarquia visual clara, sin estetica
  cyberpunk/IA generica.

## Pendiente inmediato
- Commit + push a `main` y `BackEnd/First` (Render y Vercel auto-despliegan).
- Cliente: habilitar Gmail API con scope `gmail.send`, redirect URI del OAuth
  Playground, refresh token, y fijar en Render `MAIL_TRANSPORT=gmail` +
  `GOOGLE_MAIL_REFRESH_TOKEN`.
- Resolver ejecucion de tests backend en CI (configurar test-compile phase).
- Escribir tests backend para `AnalystAccessRequestService` y `Controller`.
- Revision juridica de los cinco documentos legales.

## Reglas nuevas promovidas
- R-53 consentimiento en servidor/ misma transaccion; reenvio anti-enumeracion.
- R-54 el pie y lo que debe sobrevivir a fallos usan `<a href>`, nunca `<Link>`.