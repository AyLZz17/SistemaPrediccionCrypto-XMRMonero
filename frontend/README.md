# XMR-Forecast — Frontend

Aplicacion web (React 18 + TypeScript + Vite + Tailwind) para consultar la
**capacidad predictiva evaluada** de modelos LSTM/GRU frente a las lineas base
(media movil, regresion lineal, ARIMA) sobre la serie diaria de **XMR-USD**.

> **No es asesoria financiera.** La plataforma no promete rentabilidad y no
> simula operaciones ni backtesting de trading. El aviso legal (R-11) es visible
> en la landing, el dashboard, las predicciones, las metricas, el login y el
> registro, y se incluye en este README.

---

## 1. Indice

1. [Estado del proyecto](#2-estado-del-proyecto)
2. [Comandos](#3-comandos)
3. [Variables de entorno](#4-variables-de-entorno)
4. [Footer obligatorio](#5-footer-obligatorio)
5. [Rutas, autenticacion y autorizacion](#6-rutas-autenticacion-y-autorizacion)
6. [Sesion y renovacion silenciosa](#7-sesion-y-renovacion-silenciosa)
7. [Contrato de API](#8-contrato-de-api)
8. [Google OAuth](#9-google-oauth)
9. [Manejo de errores](#10-manejo-de-errores)
10. [Design tokens](#11-design-tokens)
11. [Accesibilidad](#12-accesibilidad)
12. [Pruebas](#13-pruebas)
13. [Despliegue (Docker + nginx)](#14-despliegue-docker--nginx)
14. [Seguridad por ruta (R-35)](#15-seguridad-por-ruta-r-35)
15. [Reglas del proyecto que aplica este frontend](#16-reglas-del-proyecto-que-aplica-este-frontend)

---

## 2. Estado del proyecto

El backend Spring Boot (`backend/`) **todavia no existe en el repositorio** (ver
`AGENTS.md` T-023). Este frontend esta completo y verificado contra el contrato
de API descrito en la seccion 8, pero **no se ha integrado contra un backend real**:
ninguna cifra de rendimiento o de prediccion procede de una corrida real.

Todo lo que este README afirma sobre comportamiento esta cubierto por las
pruebas automatizadas de la seccion 13.

## 3. Comandos

```bash
npm install          # instala dependencias segun package-lock.json (R-17)
npm run dev          # servidor de desarrollo (https si hay certificados)
npm run typecheck    # tsc --noEmit
npm run build        # tsc --noEmit && vite build
npm run preview      # sirve dist/ en local
npm run lint         # eslint, --max-warnings 0
npm run test         # vitest run
npm run test:watch   # vitest en modo watch
```

### Desarrollo con HTTPS (R-33)

El servidor de Vite usa HTTPS si encuentra los certificados en `certs/cert.pem`
y `certs/key.pem` (ambos ignorados por git). Para generarlos localmente:

```bash
mkdir -p certs
openssl req -x509 -newkey rsa:4096 -nodes -days 365 \
  -keyout certs/key.pem -out certs/cert.pem \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

O define `VITE_DEV_HTTPS_CERT` / `VITE_DEV_HTTPS_KEY` con otras rutas.

## 4. Variables de entorno

Copia `.env.example` a `.env.local` (ignorado por git). **Solo variables sin
secretos**: todo lo que lleve el prefijo `VITE_` se incrusta en el bundle publico.

| Variable | Obligatoria | Descripcion |
|----------|-------------|-------------|
| `VITE_API_BASE_URL` | si | Origen **https://** del backend Spring Boot. Sin `/api/v1` (se antepone solo). |
| `VITE_REFRESH_TOKEN_MODE` | no | `cookie` (recomendado en produccion) o `body`. |
| `VITE_REQUEST_TIMEOUT_MS` | no | Timeout por peticion, 1000..120000. Def. `20000`. |

`src/config/env.ts` **aborta el arranque** (`EnvValidationError`) si:

- falta `VITE_API_BASE_URL` o no es una URL absoluta;
- el esquema no es `https://` (incluido `http://localhost`: TLS es obligatorio en
  todos los entornos, R-33);
- la URL lleva credenciales embebidas, query string o fragmento;
- `VITE_REFRESH_TOKEN_MODE` no es `body` ni `cookie`;
- `VITE_REQUEST_TIMEOUT_MS` esta fuera de rango;
- hay una variable `VITE_*` desconocida **o con aspecto de secreto**
  (`*SECRET*`, `*PASSWORD*`, `*API_KEY*`, `*PRIVATE_KEY*`, `*CREDENTIAL*`, …).

## 5. Footer obligatorio

Un unico componente reutilizable, `src/components/layout/Footer.tsx`, renderiza
**exactamente** esta cadena:

```
Sistema esta realizado por © AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.
```

La constante se declara con el escape explicito `\u00A9` para que ningun cambio de
codificacion del fuente altere los caracteres.

Se renderiza en:

| Ubicacion | Fichero |
|-----------|---------|
| Rutas publicas | `PublicLayout.tsx` |
| Rutas autenticadas | `AuthenticatedLayout.tsx` |
| Ruta `*` (404) | `pages/NotFoundPage.tsx` |
| Error de renderizado | `common/ErrorBoundary.tsx` |
| Esqueletos de arranque y de verificacion de permisos | `routes/ProtectedRoute.tsx` |

`src/test/footer-on-every-route.test.tsx` monta **cada ruta** (publicas, protegidas,
admin, 404, redireccion de anonimos, esqueleto de arranque y estado 403) y
comprueba el literal caracter a caracter. Si alguien elimina el footer de una
vista, la suite falla.

## 6. Rutas, autenticacion y autorizacion

### Mapa de rutas

| Ruta | Autenticacion | Rol minimo |
|------|---------------|------------|
| `/` landing | publica | — |
| `/login`, `/register`, `/forgot-password`, `/reset-password` | publica (redirigen a `/dashboard` si hay sesion) | — |
| `/auth/callback` (Google OAuth) | publica | — |
| `/dashboard` | sesion | VIEWER |
| `/market` | sesion | VIEWER |
| `/predictions` | sesion | VIEWER (generar: ANALYST) |
| `/jobs` | sesion | VIEWER (cancelar: VIEWER) |
| `/account` | sesion | VIEWER |
| `/experiments` | sesion | ANALYST |
| `/metrics` | sesion | ANALYST |
| `/models` (comparativa) | sesion | ANALYST (promover: ADMIN) |
| `/admin` (usuarios y roles) | sesion | ADMIN |
| `/admin/audit` | sesion | ADMIN |
| `/admin/logs` | sesion | ADMIN |
| `*` | publica | — (404 con footer propio) |

### Guardas

- `ProtectedRoute` — exige sesion valida. Mientras la renovacion silenciosa esta
  en curso renderiza un esqueleto oscuro (nunca un flash del login). Si no hay
  sesion redirige a `/login` con `state.from`, de modo que el usuario vuelve a la
  ruta que pretendia.
- `RoleRoute` — exige `VIEWER` / `ANALYST` / `ADMIN`. Se monta **dentro** del
  layout autenticado, asi que el estado 403 conserva el footer obligatorio.
- `GuestOnlyRoute` — mantiene a un usuario autenticado fuera de `/login` y
  `/register`.

La lista de navegacion (`NAV_GROUPS` en `Sidebar.tsx`) y las guardas comparten la
misma politica de roles, de forma que el menu y la proteccion no pueden
divergir. El backend revalida cada peticion en cualquier caso (R-27): la UI solo
evita ida y vuelta inutiles.

## 7. Sesion y renovacion silenciosa

- **Token de acceso**: solo en memoria (zustand). Nunca se escribe en
  `localStorage` ni en `sessionStorage`.
- **Token de refresco**:
  - `VITE_REFRESH_TOKEN_MODE=cookie` — el backend lo fija como cookie
    `httpOnly` + `Secure` + `SameSite`; el JavaScript nunca lo ve. El cliente envia
    `credentials: 'include'`.
  - `VITE_REFRESH_TOKEN_MODE=body` — el backend lo devuelve en el `TokenResponse`;
    el frontend lo guarda en `sessionStorage` (se pierde al cerrar la pestana),
    nunca en `localStorage`.
- **Renovacion silenciosa**: ante un `401`, `apiRequest` invoca
  `refreshAccessToken()`, que es **single-flight**: N peticiones paralelas que
  fallan con 401 producen **exactamente un** `POST /api/v1/auth/refresh` y luego
  cada peticion se reproduce una sola vez. Si el backend responde
  `TOKEN_EXPIRED` o `TOKEN_REVOKED`, la sesion se descarta sin intentar renovar
  (un token muerto no se reproduce) y el usuario vuelve al login.
- **Arranque**: tras recargar la pagina no hay token de acceso, asi que
  `bootstrapSession()` intenta una renovacion silenciosa una sola vez
  (tambien memoizada). Si no hay material de refresco, marca la sesion como
  anonima sin llamar a la red.
- **Logout**: `POST /api/v1/auth/logout` con el refresh token (revocacion del lado
  servidor, 204) y despues se borra el estado local. Si la revocacion falla, la
  sesion local se limpia igualmente.

## 8. Contrato de API

Todas las peticiones salen por `src/api/client.ts` y van **solo** al backend
Spring Boot definido en `VITE_API_BASE_URL`. El frontend no conoce ni acepta
PostgreSQL, Redis, MLflow ni el servicio FastAPI-ML: el servicio ML no es un
origen configurado (R-32).

| Metodo | Ruta | Uso |
|--------|------|-----|
| POST | `/api/v1/auth/register` | Alta de cuenta |
| POST | `/api/v1/auth/login` | `TokenResponse` |
| POST | `/api/v1/auth/refresh` | Renovacion (single-flight) |
| POST | `/api/v1/auth/logout` | Revocacion (204) |
| GET | `/api/v1/auth/me` | Usuario actual |
| GET | `/api/v1/auth/google/authorize` | Redireccion 302 a Google |
| POST | `/api/v1/auth/google/callback` | Canje de `code` -> `TokenResponse` |
| POST | `/api/v1/auth/password/forgot` · `/reset` · `/change` | Recuperacion y cambio |
| GET | `/api/v1/market/candles` · `/latest` | Velas y precio |
| GET/POST | `/api/v1/datasets` | Datasets |
| GET/POST | `/api/v1/experiments` · `/runs` | Experimentos y corridas |
| GET | `/api/v1/models` · `/models/{id}/versions` | Modelos y versiones |
| POST | `/api/v1/models/{id}/promote` | Promocion de campeon (ADMIN) |
| GET/POST | `/api/v1/predictions` · `/{id}` | Predicciones |
| GET | `/api/v1/metrics/compare` · `/experiments/{id}` | Metricas |
| GET | `/api/v1/jobs` · `/{id}` · POST `/{id}/cancel` | Monitor de tareas |
| GET | `/api/v1/notifications` · POST `/{id}/read` | Notificaciones |
| GET | `/api/v1/audit` · `/users` · PATCH `/users/{id}/role` | Administracion |

Sobre el contrato documentado hay **dos adaptaciones** que conviene conocer:

1. El contrato define `GET /api/v1/auth/google/callback?code&state`, pero el
   cliente se declara con un metodo en la misma ruta. `api/auth.ts` envia
   **`POST /api/v1/auth/google/callback`** con `{code, state}`. Si el backend
   implementa el verbo `GET`, hay que cambiar una sola linea en
   `src/api/auth.ts` (`rawRequest(..., { method: 'POST' })` -> `'GET'`, con el
   `code`/`state` en `query` en lugar de `body`).
2. `GET /api/v1/models` se consume con el sobre paginado
   `{items,page,size,total,totalPages}`; los endpoints de modelos y comparativa
   aceptan tambien una lista plana (`ensurePage` / normalizadores en
   `src/api/experiments.ts` e `index.ts` degradan con elegancia).

**Correlacion**: cada peticion lleva `X-Request-Id` (UUID generado en el
cliente). Si la respuesta trae `X-Request-Id`, ese valor gana. Los errores
muestran el `requestId` en la alerta y en los estados de error, de modo que el
recorrido frontend -> backend -> servicio ML es rastreable desde la UI (R-26/R-32).

## 9. Google OAuth

Flujo **gestionado por el backend** (Authorization Code + OIDC). El frontend:

1. Muestra un enlace real (`<a>`, accesible por teclado, con middle-click) cuyo
   `href` es `https://<backend>/api/v1/auth/google/authorize?returnTo=...`.
2. El backend responde 302 hacia Google.
3. Google vuelve a `<backend>/api/v1/auth/google/callback?code&state=...`, que
   redirige a la SPA en `/auth/callback`.
4. Esa pagina **postea el `code` al backend** (nunca a Google), muestra un
   esqueleto de carga, guarda la sesion y navega a la ruta de destino.

**No existe ningun client secret de Google en el bundle.** El test
`google-oauth.test.tsx` comprueba que el `href` no contiene `client_id`,
`client_secret`, `refresh_token` ni `accounts.google.com`, y que el intercambio
va contra el backend.

Estados gestionados: `?error=...` (denegado), `code` ausente y fallo del
intercambio (500 con su `requestId`).

## 10. Manejo de errores

`src/api/errors.ts` normaliza cualquier fallo a `ApiError` con un mensaje
amigable **distinto por clase**, y nunca muestra el mensaje interno del servidor
ni trazas.

| Situacion | Mensaje al usuario | Reintentable |
|-----------|--------------------|--------------|
| 400 / 422 | La solicitud no es valida (+ `fieldErrors` en el formulario) | no |
| 401 | Tu sesion expiro o no es valida | no (renovacion silenciosa) |
| 403 | No tienes permisos para realizar esta accion | no |
| 404 | No encontramos el recurso solicitado | no |
| 409 | El recurso ya existe o entra en conflicto | no |
| 429 | Demasiadas solicitudes seguidas… | si |
| 500 | El servidor encontro un error inesperado | si |
| 502 / 503 / 504 | El servicio no esta disponible temporalmente | si |
| timeout | El servidor tardo demasiado en responder… | si |
| red | No pudimos conectar con el servidor… | si |

Los reintentos de React Query se desactivan automaticamente para cualquier
`4xx` (`shouldRetry` en `App.tsx`): reintentar un 403 no lo convierte en 200.

## 11. Design tokens

Todos los valores viven en `src/styles/theme.css` como variables CSS
personalizadas y se exponen a Tailwind por `tailwind.config.js`
(`theme.extend`). **No hay hex sueltos en los componentes.**

- Fondo negro profundo, paneles de carbon, tarjetas de cristal translucido
  (`backdrop-blur`), bordes semitransparentes y resplandor sutil en activo/hover.
- Acentos (verde neon / cian / rojo / ambar) **solo** para estado, alerta y
  metricas; nunca como relleno grande.
- Tipografia monoespaciada para terminales, logs, metricas e identificadores
  (`.mono`, `.label-caps`, `font-mono`).
- Modo oscuro permanente (`color-scheme: dark`); no existe tema claro.
- Los acentos usan tripletas de canal (`--xmr-accent-cyan-ch`) para que los
  modificadores de opacidad de Tailwind (`border-accent-cyan/40`) apliquen
  alfa real en lugar de ignorarse.
- Estados: esqueletos oscuros, estados vacios, estados de error, 404 y modal
  trampa de foco.
- Indicadores de actividad/conexion/seguridad/ejecucion (`StatusDot`,
  `ActivityBar`, `Badge`) con animacion sutil y etiqueta textual (no solo
  color). `prefers-reduced-motion` desactiva las transiciones.
- Responsive: sidebar colapsable en escritorio, cajon deslizante en movil.

## 12. Accesibilidad

- Landmarks: `banner`, `navigation`, `complementary`, `main`, `contentinfo`, con
  enlace "Saltar al contenido principal".
- Contraste WCAG AA calculado sobre `--xmr-bg-deep` (#080b10):
  texto primario 15.2:1, secundario 9.6:1, atenuado 5.9:1 (cuerpo >= 4.5:1);
  verde 11.3:1, cian 10.9:1, rojo 6.1:1, ambar 10.0:1 (texto grande / UI >= 3:1).
- Foco visible en todo elemento interactivo mediante `:focus-visible` con anillo
  de doble anillo; navegacion completa por teclado.
- Formularios: `<label>` asociado, `aria-invalid`, `aria-describedby`, errores con
  `role="alert"`.
- Tablas: `<caption>`, `scope="col"`, celdas numericas alineadas y en monoespaciada.
- Modales: `role="dialog"`, `aria-modal`, foco inicial, **trampa de foco**,
  cierre con `Escape` y devolucion del foco al elemento de origen.
- `prefers-reduced-motion` respetado.

## 13. Pruebas

144 pruebas en 8 ficheros (`npm run test`). **Ninguna hace llamadas de red**: el
`fetch` global se sustituye por un stub (R-18).

| Fichero | Cobertura |
|---------|-----------|
| `footer-on-every-route.test.tsx` | Literal del footer en cada ruta, 404, redireccion, esqueleto de arranque y estado 403 |
| `route-protection.test.tsx` | Redireccion de anonimos, `state.from`, puerta por rol, recuperacion de sesion tras recarga, 401 |
| `auth-flows.test.tsx` | Login, registro (validacion local y errores de servidor), logout con revocacion, recuperacion y cambio de contrasena, 429, el token de acceso nunca se persiste |
| `google-oauth.test.tsx` | Boton/enlace, destino al authorize del backend, esqueleto del callback, canje, destino de retorno, `?error=`, 500 |
| `api-client.test.ts` | Single-flight del refresco (N 401 -> 1 refresh), re-arme tras fallo, 401/403/429/500/503/timeout/red, `fieldErrors`, `requestId`, cabeceras, `credentials`, unica salida |
| `env.test.ts` | Validador de entorno: https obligatorio, secretos rechazados, rangos, variables desconocidas |
| `ui-components.test.tsx` | Button, campos, DataTable, Pagination, Modal (foco/Escape), alerts, estados, indicadores, StatusPill, Disclaimer (R-11/R-12), ErrorBoundary |
| `layout-accessibility.test.tsx` | Footer, PublicLayout, AuthenticatedLayout, Sidebar, Header, ForbiddenPanel, landmarks y teclado |

## 14. Despliegue (Docker + nginx)

```bash
docker build \
  --build-arg VITE_API_BASE_URL=https://api.xmr-forecast.example \
  --build-arg VITE_REFRESH_TOKEN_MODE=cookie \
  -t xmr-forecast-frontend:latest .

docker run --rm -p 8080:8080 xmr-forecast-frontend:latest
```

- **Multi-etapa**: `node:22.14.0-alpine` compila (`npm ci` sobre el lockfile) y
  `nginxinc/nginx-unprivileged:1.27-alpine` sirve `dist/`.
- `nginx.conf`:
  - proxy de `/api/` al backend por **HTTPS** con `proxy_ssl_verify on`,
    `X-Forwarded-Proto https` y reenvio de `X-Request-Id`;
  - `Cache-Control: no-store` en las respuestas de API y en `index.html`;
    `immutable` de un año para `/assets/` (nombres con hash);
  - cabeceras: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
    `Referrer-Policy: no-referrer`, `Permissions-Policy`,
    `Cross-Origin-Opener-Policy`/`-Resource-Policy: same-origin`,
    `Strict-Transport-Security: max-age=63072000; includeSubDomains; preload`,
    `server_tokens off`, y CSP con `default-src 'self'`,
    `object-src 'none'`, `frame-ancestors 'none'`, `base-uri 'self'`,
    `form-action 'self'`, `script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
    `img-src 'self' data:` y **`connect-src 'self' https:`**;
  - SPA fallback con `try_files $uri $uri/ /index.html` y `deny` en `/.`;
  - `/healthz` para el `HEALTHCHECK`.
- Terminacion TLS: el contenedor escucha en **8080** (usuario no root). El bloque
  que termina TLS en el propio nginx (443 con 80 -> **301** a https, HSTS,
  `ssl_protocols TLSv1.2 TLSv1.3`, stapling) esta comentado al final del
  fichero; activalo cuando montes `/etc/nginx/tls/fullchain.pem` y
  `/etc/nginx/tls/privkey.pem` y cambies el `EXPOSE`. Los certificados no se
  versionan.
- `docker-compose.yml` levanta **solo** el frontend; el backend se ejecuta fuera.

## 15. Seguridad por ruta (R-35)

| Control | Donde |
|---------|-------|
| Autenticacion | `ProtectedRoute` + `Authorization: Bearer` en `api/client.ts` |
| Autorizacion | `RoleRoute` + `NAV_GROUPS.minimum`; revalidada por el backend |
| Validacion de entrada | `Field.tsx` (tipos, `required`, `minLength`) y reglas locales en formularios + `fieldErrors` del servidor |
| Limites | Tamano de pagina fijo (15/20/30), `maxDuration` de la UI, 2 MB de cuerpo en nginx, `client_max_body_size` |
| Datos tratados | Correo, nombre, token de acceso (memoria) y token de refresco (cookie httpOnly o `sessionStorage`). Ninguno se registra en `localStorage`. |
| Amenazas | XSS (CSP `script-src 'self'`, React escapa por defecto, sin `dangerouslySetInnerHTML`), CSRF (Bearer en cabecera + `SameSite`), robo de token (memoria + httpOnly), fijacion de sesion (rotacion en `/refresh`), open redirect (retorno limitado a rutas internas), DoS porStorm de refresh (single-flight), fuga de secretos (validador de entorno) |
| Trazabilidad | `X-Request-Id` en cliente y servidor; visible en errores y en la consola de logs |
| Pruebas negativas | Anonimos -> login, VIEWER -> 403 en admin, 401/403/429/500/timeout/red, campos de formulario, puerta del callback OAuth |

## 16. Reglas del proyecto que aplica este frontend

| Regla | Aplicacion |
|-------|------------|
| R-11 | Componente `Disclaimer` en landing, dashboard, predicciones, metricas, login y registro. Sin backtesting ni simulacion de operaciones en ninguna vista. |
| R-12 | Se usa "capacidad predictiva evaluada". El test `ui-components.test.tsx` verifica que la cadena prohibida no aparece. |
| R-14 | `.env.example` solo con variables publicas; el validador rechaza variables con aspecto de secreto; `certs/` ignorado por git. |
| R-17 | Versiones fijadas en `package.json` y `package-lock.json`; la imagen usa `npm ci`. |
| R-18 | Ninguna prueba hace red: `fetch` se sustituye por stubs. |
| R-25 | Sin diagramas Mermaid en este frontend (no aplica). |
| R-27 | HTTPS obligatorio en el validador, HSTS en nginx, secretos fuera del bundle. |
| R-32 | Unica salida: el backend Spring Boot. El servicio ML no es un origen configurado. |
| R-33 | `https://` obligatorio en `VITE_API_BASE_URL`; TLS en Vite (dev) y en nginx (prod). |
| R-35 | Ver seccion 15. |
| R-36 | El backend es Java 21; el frontend no fija la version de Java y no interactua con ella. |

## 17. Estructura

```
frontend/
├── index.html · nginx.conf · Dockerfile · docker-compose.yml
├── .env.example · .gitignore · .eslintrc.cjs
├── tailwind.config.js · postcss.config.js · vite.config.ts · tsconfig*.json
└── src/
    ├── main.tsx · App.tsx · AppRoutes.tsx · index.css
    ├── api/        client, errors, requestId, auth, market, experiments, index
    ├── auth/       tokenStorage, rawRequest, refreshCoordinator, session
    ├── components/ layout/ (Footer, PublicLayout, AuthenticatedLayout, Sidebar, Header)
    │               ui/      (Button, Panel, Badge, Field, DataTable, Modal, Alert,
    │                         States, Indicators)
    │               common/  (Disclaimer, ErrorBoundary, StatusPill)
    │               routes/  (ProtectedRoute, RoleRoute, ForbiddenPanel)
    │               charts/  (CandlestickChart, MetricComparisonChart, Sparkline)
    │               auth/    (GoogleButton)
    ├── config/     env.ts (validador)
    ├── store/      authStore.ts (zustand)
    ├── styles/     theme.css (tokens)
    ├── types/      auth, session, domain, market, pagination
    ├── utils/      format.ts
    └── test/       setup, testUtils, fetchStub + 8 suites
```
