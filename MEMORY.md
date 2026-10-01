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

## Tarea en curso (T-042, hecha)
- Legalidad + consentimiento + reenvio de verificacion + arreglo de Google.
- Registro exige `acceptTerms` y `acceptDataPolicy` (400 `VALIDATION_FAILED` con los
  dos campos; el servicio responde ademas `400 CONSENT_REQUIRED`, que es el codigo del
  flujo de Google). Filas en `consent_records` (V6) con version/IP en la MISMA
  transaccion (R-53).
- `POST /auth/verify-email/resend` publico: 204 exista o no la cuenta, rate
  limit, caduca el token anterior. Pantalla de reenvio en `VerifyEmailPage`.
- Google: consentimiento dentro del `state`; cuenta nueva solo con el y si no
  `error_code=CONSENT_REQUIRED` (panel con checkboxes en `GoogleCallbackPage`).

## Verificado en esta sesion
- Backend `mvn clean verify` = **186 tests, BUILD SUCCESS** (Corretto 21.0.12).
- Frontend `npm run lint` = 0 · `npm test` = **169/169** · `npm run build` OK.
- Pila completa Docker (6 servicios healthy) + `tools/verify-stack.ps1` =
  **53/53 comprobaciones OK**; Flyway `V1..V6` sobre PostgreSQL 15.19, **23 tablas**.
- Produccion tras el push `2faa49d`: `meta/legal` 200 (5 docs, `2026-10-01`);
  registro sin aceptes 400 con los dos campos, con aceptes 201; reenvio 204 x2
  con cuerpos identicos; Vercel `/terms` 200, pie literal + 5 enlaces, boxes sin
  marcar.
- Inventario real: 48 metodos de mapeo en 13 controladores (docs/01 y 05
  actualizados de 46 a 48).
- Contratos nuevos: `RegisterPayloadContractTest`, `LegalVersionsContractTest`.
- Correccion de contrato: por HTTP el registro sin aceptes da `400 VALIDATION_FAILED`
  con los dos campos (va la validacion de bean), no `CONSENT_REQUIRED`; ese codigo
  es el del servicio y del flujo de Google. Arreglado en docs/01, README y E2E.

## Pendiente inmediato
- Commit + push a `main` y `BackEnd/First` (Render y Vercel auto-despliegan).
- Cliente: habilitar Gmail API con scope `gmail.send`, redirect URI del OAuth
  Playground, refresh token, y fijar en Render `MAIL_TRANSPORT=gmail` +
  `GOOGLE_MAIL_REFRESH_TOKEN`. Entonces reintentar registro y Google.
- Sin resolver: rotacion de credenciales expuestas, `ML_SERVICE_URL` con
  `sync: false`, callback de Google Cloud, supuesto **D-14** (dashboard publico),
  revision juridica de los cinco documentos legales.

## Reglas nuevas promovidas
- R-53 consentimiento en servidor/ misma transaccion; reenvio anti-enumeracion.
- R-54 el pie y lo que debe sobrevivir a fallos usan `<a href>`, nunca `<Link>`.
