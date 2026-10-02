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

## Tarea en curso (T-045, hecha)
- Refinado visual del HUD: negro profundo, paneles oscuros, cian solo en
  foco/interaccion/conectividad, rojo solo caida/error, verde subida/exito,
  ambar advertencia. Sin glassmorphism decorativo ni violeta.
- Cambios: MetricCard con valor en tinta (no cian), `Panel tone=accent`
  neutro, Alert info neutro, StatusPill roles neutros, BrandMark neutro,
  strip de AuthenticatedLayout sin ActivityBar indeterminado, PublicLayout
  con CTA neutras y header `min-h-header`, landing sin circulo blur,
  sparkline de Dashboard con velas reales (antes inventado, R-21),
  MetricComparisonChart sin serie roja (direction ahora verde, MAPE gris),
  arreglo `text-bg-root` invalido en Header y TS2322 preexistente en
  `types/analyst.ts` (T-044 decia build OK; `git stash`+tsc lo desmintio).

## Verificado en esta sesion
- `npm run lint` = 0 · `npm test` = **183/183** · `npm run build` OK
  (tsc + vite). Vista previa local en `:4173`: `/`, `/about`, `/login`
  renderizan con backend ausente (estados de error en rojo controlado);
  captura a 360 px sin desbordes de cabecera.

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
