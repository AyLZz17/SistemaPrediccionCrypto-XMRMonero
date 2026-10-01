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

## Tarea en curso (T-043, hecha)
- Dashboard publico como primera pantalla (`/`): 6 endpoints `/api/v1/public/**`
  (summary, series, models, metrics, comparison, status). Solo GET, sin datos
  de usuario, con cache Redis y rate limit propio (120/min).
- Verificacion de email: token hash, expiracion, uso unico, reenvio 204
  anti-enumeracion. Pantallas de exito/expirado/reenvio.
- Recuperacion de contrasena: token 2h hash, uso unico, revocacion de sesiones,
  anti-enumeracion, rate limit.
- Google OAuth: state+nonce en Redis, consent gate, activacion de cuentas no
  verificadas, sin duplicados.
- Rediseño visual: tema oscuro, glassmorphism, WCAG AA, responsive.
- Tests: 249 backend + 183 frontend, todos pasan. Build exitoso.

## Verificado en esta sesion
- Backend `mvn clean verify` = **249 tests, BUILD SUCCESS** (Corretto 21.0.12).
- Frontend `npm run lint` = 0 · `npm test` = **183/183** · `npm run build` OK.
- Commit `3999288` en `main` con 73 archivos cambiados.

## Pendiente inmediato
- Push a `main` y `BackEnd/First` (Render y Vercel auto-despliegan).
- Cliente: habilitar Gmail API con scope `gmail.send`, redirect URI del OAuth
  Playground, refresh token, y fijar en Render `MAIL_TRANSPORT=gmail` +
  `GOOGLE_MAIL_REFRESH_TOKEN`. Entonces reintentar registro y Google.
- Sin resolver: rotacion de credenciales expuestas, `ML_SERVICE_URL` con
  `sync: false`, callback de Google Cloud, supuesto **D-14** (dashboard publico),
  revision juridica de los cinco documentos legales.

## Reglas nuevas promovidas
- R-53 consentimiento en servidor/ misma transaccion; reenvio anti-enumeracion.
- R-54 el pie y lo que debe sobrevivir a fallos usan `<a href>`, nunca `<Link>`.
