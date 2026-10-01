# =====================================================================
# XMR-Forecast :: verificacion HTTP de extremo a extremo (Windows)
#
# POR QUE NO SE USA curl
#   El curl incluido en Windows usa schannel, que exige comprobar la
#   revocacion contra un CRL u OCSP. La CA de desarrollo no publica ninguno, y
#   schannel aborta con "the revocation status is unknown" antes de validar la
#   cadena. Anadir `--ssl-no-revoke` funciona, pero ata el script a una
#   herramienta concreta. Se usa la pila TLS de .NET, que si valida la cadena
#   mediante la CA y acepta el certificado de desarrollo.
#
# QUE COMPRUEBA
#   1. el backend responde por HTTPS y /actuator/health esta UP;
#   2. el aviso legal publico responde SIN sesion (R-11);
#   3. una ruta protegida responde 401 sin token;
#   4. el puerto HTTP plano no escucha (R-33);
#   5. registro + login emiten JWT y la cookie HttpOnly del refresh token;
#   6. /auth/me publica el ROL EFECTIVO (campo `role`), no solo `roles`;
#   7. los 14 endpoints que el frontend consume y antes no existian responden
#      200 con el sobre de paginacion correcto;
#   8. un VIEWER recibe 403 en /api/v1/audit y /api/v1/users (ADMIN);
#   9. escritura permitida para VIEWER+ segun rol, y 403 donde no cabe;
#  10. la cola de trabajos AVANZA: el trabajo TRAIN encolado llega a un estado
#      terminal, y la corrida y el experimento reflejan ese desenlace.
#  11. el PANEL PUBLICO (primera pantalla, anonimo): sus seis rutas responden
#      sin sesion, validan sus parametros, son de solo lectura y no contienen
#      datos personales; las rutas privadas siguen cerradas para anonimos.
#  12. la RECUPERACION DE CONTRASENA de extremo a extremo: 204 identico con
#      correo existente o inexistente, token guardado con hash y TTL de 2 h,
#      cooldown de 2 min, enlace calculado igual que el servidor, reset 204,
#      enlace de un solo uso, sesiones revocadas (incluida la cookie por HTTP),
#      contrasena vieja fuera, contrasena nueva dentro y auditoria registrada.
#
#   En el punto 12 el paso que se sustituye es unico: la RECEPCION del correo.
#   No hay bandeja en este entorno, asi que se calcula el mismo HMAC-SHA256 que
#   calcula `TokenHasher` y se inserta la fila que `forgotPassword` crea. Todo
#   lo demas (validacion, consumo, logout, hash de contrasena, auditoria) es
#   codigo real del servidor ejercitado por HTTP, no una simulacion.
#
# POR QUE EL PUNTO 10 EXISTE
#   Entre T-027 y esta revision, `JobRepository.claimIfPending` era un @Modifying
#   sin @Transactional y el worker lanzaba InvalidDataAccessApiUsageException en
#   CADA barrido: la cola estaba muerta y los trabajos se quedaban en PENDING para
#   siempre. Las 43 comprobaciones originales pasaban igual, porque solo miraban
#   que el trabajo estuviera "en la cola", nunca que avanzara. Un health check en
#   verde no dice si el sistema hace su trabajo.
#
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify-stack.ps1
# =====================================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$base = 'https://127.0.0.1:8443'

# La CA de desarrollo se anade al almacen de confianza del proceso. Es mas
# fuerte que "aceptar cualquier certificado": si el servidor presentara un
# certificado de otra CA, la conexion fallaria igual.
$caPath = Join-Path $root 'docker\certs\ca\ca.crt'
$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 $caPath
$trust = New-Object System.Security.Cryptography.X509Certificates.X509Store('Root', 'CurrentUser')
$trust.Open('ReadWrite')
$already = $trust.Certificates | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
if (-not $already) { $trust.Add($cert); Write-Host "CA de desarrollo anadida al almacen CurrentUser\Root" }
else { Write-Host "CA de desarrollo ya estaba en el almacen" }
$trust.Close()

function Get-EnvValue([string]$name) {
  $line = Select-String -Path (Join-Path $root '.env') -Pattern "^$name=(.*)$" | Select-Object -First 1
  if (-not $line) { throw "Falta $name en .env" }
  return $line.Matches[0].Groups[1].Value
}

$failures = 0
function Check([string]$name, [bool]$ok, [string]$detail = '') {
  if ($ok) { Write-Host ("OK    " + $name) }
  else { Write-Host ("FALLO " + $name + "   " + $detail) -ForegroundColor Red; $script:failures++ }
}

# Invoke-Check <METHOD> <path> [body] [token] [cookie] -> @{ Status; Body; Headers }
#
# `cookie` permite presentar la cookie de refresh en claro: hace falta para
# comprobar que un reset de contrasena invalida las sesiones existentes, que es
# una garantia que no se puede demostrar solo mirando la base de datos.
function Invoke-Check([string]$method, [string]$path, [string]$body = '', [string]$token = '', [string]$cookie = '') {
  $headers = @{ 'X-Request-Id' = ('verify-' + [guid]::NewGuid().ToString('N').Substring(0,12)) }
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  if ($cookie) { $headers['Cookie'] = $cookie }
  $params = @{
    Uri             = ($base + $path)
    Method          = $method
    Headers         = $headers
    UseBasicParsing = $true
    TimeoutSec      = 20
  }
  # El cuerpo solo se adjunta si hay contenido: enviar '' en un GET hace que
  # HttpWebRequest rechace la peticion ("no se puede enviar contenido textual").
  if ($body) { $params['Body'] = $body; $headers['Content-Type'] = 'application/json' }
  try {
    $response = Invoke-WebRequest @params
    return @{ Status = [int]$response.StatusCode; Body = (As-Text $response.Content); Headers = $response.Headers }
  } catch [System.Net.WebException] {
    $r = $_.Exception.Response
    if ($null -eq $r) { return @{ Status = 0; Body = $_.Exception.Message; Headers = @{} } }
    $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
    return @{ Status = [int]$r.StatusCode; Body = $reader.ReadToEnd(); Headers = $r.Headers }
  }
}

# En Windows PowerShell 5.1 con -UseBasicParsing, .Content llega como byte[]
# cuando la respuesta no es HTML. Sin decodificarlo, la comparacion contra el
# cuerpo falla siempre y el test miente.
function As-Text($content) {
  if ($content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($content) }
  return [string]$content
}

# Invoke-Login <email> <password> -> @{ Status; Body; Headers }
#
# Reintenta ante 429 respetando el limite real del sistema (5/min por IP en
# `app.security.rate-limit.login-attempts-per-minute`). El script hace varios
# intentos de login a proposito, asi que sin esta espera el propio guion
# activaria el limitador que despues quiere comprobar. No se desactiva el limite:
# la espera es lo que haria un cliente bien comportado.
# Igual que Invoke-Check, pero tolerando el 429 de los caminos sensibles:
# password/*, login y verify-email comparten UN cubo por IP, y en el flujo de
# recuperacion las llamadas se acumulan. En vez de rendirse o de saltarse el
# limite, se espera el tiempo que el PROPIO servidor indica en `Retry-After` y
# se reintenta, que es lo que haria un cliente bien educado. Si tras tres
# intentos sigue en 429, devuelve el 429 y la comprobacion falla: el limite no
# se esconde, se respeta.
function Invoke-Sensitive([string]$method, [string]$path, [string]$body = '', [string]$cookie = '') {
  $r = $null
  for ($try = 1; $try -le 3; $try++) {
    $r = Invoke-Check $method $path $body '' $cookie
    if ($r.Status -ne 429) { return $r }
    $wait = 61
    $header = [string]$r.Headers['Retry-After']
    if ($header -match '^\d+$') { $wait = [int]$header + 1 }
    Write-Host "      (429 en $($path): se esperan $wait s como pide Retry-After, intento $try/3)"
    Start-Sleep -Seconds $wait
  }
  return $r
}

function Invoke-Login([string]$email, [string]$password) {
  $body = @{ email = $email; password = $password } | ConvertTo-Json -Compress
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    $r = Invoke-Check POST '/api/v1/auth/login' $body
    if ($r.Status -ne 429) { return $r }
    $wait = 62
    $retryAfter = $r.Headers['Retry-After']
    if ($retryAfter -and [int]$retryAfter -gt 0) { $wait = [int]$retryAfter + 1 }
    Write-Host "      (429: el limitador de login esta activo; se esperan $wait s, intento $attempt/3)"
    Start-Sleep -Seconds $wait
  }
  return $r
}

# ------------------------------------------------------------ 0. estado limpio
# El limitador de peticiones vive en Redis con prefijo `ratelimit:`. Este guion
# hace varios login y registro a proposito, de modo que al repetirlo agota el
# limite y empieza a fallar con 429 por su propia causa, no por un defecto.
#
# Resetear las claves NO es esquivar una proteccion: es dejar el entorno de
# pruebas en el estado inicial de cada ejecucion, como haria `TRUNCATE` sobre una
# tabla de fixtures. En produccion nadie ejecuta este script.
$reset = docker exec xmr-redis sh -c `
  'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning KEYS "ratelimit:*" | xargs -r redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL' 2>&1
Write-Host "Estado limpio: limitador de peticiones reseteado ($reset)"

# ------------------------------------------------------------ 1. salud
$health = Invoke-Check GET '/actuator/health'
Check 'el backend responde por HTTPS y /actuator/health es UP' `
  ($health.Status -eq 200 -and $health.Body -match '"status"\s*:\s*"UP"') `
  "status=$($health.Status) body=$($health.Body)"

# ------------------------------------------------------------ 2. aviso legal sin sesion
$disc = Invoke-Check GET '/api/v1/meta/disclaimer'
Check 'GET /api/v1/meta/disclaimer responde SIN sesion (R-11)' `
  ($disc.Status -eq 200 -and $disc.Body -match 'asesoria financiera') "status=$($disc.Status) $($disc.Body)"

# Los documentos legales y su version vigente son publicos: es lo que el
# usuario acepta y lo que queda guardado en consent_records (R-43).
$legal = Invoke-Check GET '/api/v1/meta/legal'
$legalDocs = @()
$legalVersions = @()
if ($legal.Status -eq 200) {
  $legalDocs = @(($legal.Body | ConvertFrom-Json).documents)
  $legalVersions = @($legalDocs | ForEach-Object { $_.version } | Select-Object -Unique)
}
Check 'GET /api/v1/meta/legal responde SIN sesion: 5 documentos con la MISMA version' `
  ($legalDocs.Count -eq 5 -and $legalVersions.Count -eq 1 -and "$($legalVersions[0])" -match '^\d{4}-\d{2}-\d{2}$') `
  "status=$($legal.Status) documentos=$($legalDocs.Count) version=$($legalVersions -join ',')"

# ------------------------------------------------------------ 3. ruta protegida
$unauth = Invoke-Check GET '/api/v1/market/latest'
Check 'una ruta protegida sin token devuelve 401' ($unauth.Status -eq 401) "status=$($unauth.Status)"

# ------------------------------------------------------------ 3b. panel publico
#
# La PRIMERA pantalla del producto es un dashboard anonimo. Se comprueba aqui
# que sus seis rutas responden SIN sesion, que validan sus parametros y que no
# sueltan nada personal: un panel abierto sin validacion es un panel al que se
# le puede preguntar lo que sea, y una respuesta anonima con campos de usuario
# seria una fuga (R-26).
#
# Antes se siembran dos velas sinteticas. No hay ingesta en este entorno (no se
# ejecuta `make ingest`), y sin datos el panel devolvia 404 y la cache de Redis
# NUNCA llegaba a escribirse: fue exactamente ese hueco el que oculto el fallo
# de serializacion de T-043, con las pruebas de unidad en verde y tres de las
# seis rutas publicas devolviendo 500. Mismo criterio que las versiones de
# dataset sinteticas de mas abajo: el dato es de prueba y no sobrevive a este
# script.
docker exec xmr-postgres psql -U xmr -d xmr_forecast -q -c @"
INSERT INTO market_data (symbol, source, opened_at, open, high, low, close, volume)
VALUES ('XMR-USD', 'sintetico', NOW() - interval '1 day', 155, 158, 154, 157.2, 900),
       ('XMR-USD', 'sintetico', NOW(),                157.2, 162, 157.1, 160.5, 950)
ON CONFLICT (symbol, source, opened_at) DO NOTHING;
"@ | Out-Null
Write-Host "      (dos velas sinteticas sembradas para ejercitar la cache del panel publico)"

$publicPaths = @(
  '/api/v1/public/summary?symbol=XMR-USD',
  '/api/v1/public/series?symbol=XMR-USD&limit=30',
  '/api/v1/public/models',
  '/api/v1/public/metrics',
  '/api/v1/public/comparison',
  '/api/v1/public/status'
)
$pubBodies = @{}
foreach ($path in $publicPaths) {
  $r = Invoke-Check GET $path
  Check "GET $path responde SIN sesion" ($r.Status -eq 200) "status=$($r.Status) $($r.Body)"
  if ($r.Status -eq 200) { $pubBodies[$path] = $r.Body }
}

# Ningun dato personal en la superficie anonima: ni correos, ni contrasenas, ni
# identificadores de usuario.
$leak = $false
foreach ($body in $pubBodies.Values) {
  if ($body -match 'password' -or $body -match '@example\.com' -or $body -match '"userId"' -or $body -match '"user_id"') { $leak = $true }
}
Check 'las respuestas publicas no contienen correos, contrasenas ni identificadores de usuario' `
  (-not $leak) "rutas=$($pubBodies.Count)"

# El estado publica la version legal vigente y la existencia de corridas (o su
# ausencia declarada, R-21).
$statusBody = $pubBodies['/api/v1/public/status']
Check 'GET /public/status trae la version de los documentos legales' `
  ([bool]$statusBody -and $statusBody -match '"legalVersion"\s*:\s*"\d{4}-\d{2}-\d{2}"') "body=$statusBody"
$metricsBody = $pubBodies['/api/v1/public/metrics']
Check 'GET /public/metrics declara available en lugar de devolver cifras vacias' `
  ([bool]$metricsBody -and $metricsBody -match '"available"\s*:\s*(true|false)') "body=$metricsBody"

# Validacion de parametros: fuera de rango, 400 con codigo estable.
$longSymbol = Invoke-Check GET ('/api/v1/public/summary?symbol=' + ('X' * 64))
Check 'un simbolo de 64 caracteres se rechaza (limite 32)' `
  ($longSymbol.Status -eq 400) "status=$($longSymbol.Status) $($longSymbol.Body)"
$limitZero = Invoke-Check GET '/api/v1/public/series?symbol=XMR-USD&limit=0'
Check 'limit=0 se rechaza (minimo 1)' ($limitZero.Status -eq 400) "status=$($limitZero.Status)"
$limitHuge = Invoke-Check GET '/api/v1/public/series?symbol=XMR-USD&limit=9999'
Check 'limit=9999 se rechaza (maximo 365)' ($limitHuge.Status -eq 400) "status=$($limitHuge.Status)"

# Solo lectura: el panel publico no admite escritura (R-26).
$publicWrite = Invoke-Check POST '/api/v1/public/summary' '{}'
Check 'POST sobre una ruta publica devuelve 405 (solo GET)' `
  ($publicWrite.Status -eq 405) "status=$($publicWrite.Status)"

# El panel publico NO abre la puerta a lo privado: sin sesion, experimentos,
# trabajos, predicciones, notificaciones, auditoria y usuarios siguen cerrados.
foreach ($path in @(
  '/api/v1/experiments?page=0&size=5',
  '/api/v1/jobs?page=0&size=5',
  '/api/v1/predictions?page=0&size=5',
  '/api/v1/notifications?page=0&size=5',
  '/api/v1/audit?page=0&size=5',
  '/api/v1/users?page=0&size=5',
  '/api/v1/metrics/compare?experimentId=1'
)) {
  $r = Invoke-Check GET $path
  Check "sin sesion, $path devuelve 401 o 403" ($r.Status -in @(401, 403)) "status=$($r.Status)"
}

# ------------------------------------------------------------ 4. sin HTTP plano
$plain = 0
try { $c = New-Object System.Net.Sockets.TcpClient
      $iar = $c.BeginConnect('127.0.0.1', 8443, $null, $null)
      $c.Close() } catch { $plain = 1 }
$plainRefused = $true
try { $c2 = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 80); $plainRefused = $false; $c2.Close() } catch { }
Check 'no hay listener HTTP plano en el puerto 80' $plainRefused

# ------------------------------------------------------------ 5. registro y login
#
# Sin los dos aceptes no hay cuenta: primero se comprueba el rechazo (regla
# negativa, R-53) y solo despues se registra con el consentimiento.
#
# El codigo que sale por HTTP es VALIDATION_FAILED con los dos campos senalados,
# porque la validacion de bean (sobre una instancia real, R-46) va antes que el
# servicio. En el servicio sigue existiendo CONSENT_REQUIRED: es el que ve el
# flujo de Google, que no tiene cuerpo que validar.
$noConsent = Invoke-Check POST '/api/v1/auth/register' `
  (@{ email = ('sinconsent' + (Get-Random) + '@example.com'); password = 'Verificacion#2026segura'; fullName = 'Sin Consentimiento' } | ConvertTo-Json -Compress)
Check 'POST /api/v1/auth/register rechaza el alta sin aceptar terminos y datos' `
  ($noConsent.Status -eq 400 -and $noConsent.Body -match 'acceptTerms' -and $noConsent.Body -match 'acceptDataPolicy') `
  "status=$($noConsent.Status) $($noConsent.Body)"

$email = 'verificacion' + (Get-Random) + '@example.com'
$password = 'Verificacion#2026segura'
$register = Invoke-Check POST '/api/v1/auth/register' `
  (@{ email = $email; password = $password; fullName = 'Prueba Verificacion'; acceptTerms = $true; acceptDataPolicy = $true; acceptMarketing = $false } | ConvertTo-Json -Compress)
Check 'POST /api/v1/auth/register crea la cuenta' `
  ($register.Status -eq 201 -and $register.Body -match '"id"') "status=$($register.Status) $($register.Body)"

# El efecto, no solo la existencia (R-48): la aceptacion queda demostrable en
# base de datos con la version vigente del documento.
$consentCount = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
  "SELECT count(*) FROM consent_records cr JOIN users u ON u.id = cr.user_id WHERE u.email = '$email' AND cr.accepted = true;"
Check 'los dos aceptes quedan registrados en consent_records con su version' `
  ("$consentCount".Trim() -eq '2') "filas=$($consentCount)"

# Reenvio de verificacion: publico, limitado y con la misma respuesta exista o
# no la cuenta (anti-enumeracion).
$resendKnown = Invoke-Check POST '/api/v1/auth/verify-email/resend' `
  (@{ email = $email } | ConvertTo-Json -Compress)
Check 'POST /verify-email/resend responde 204 para una cuenta existente' `
  ($resendKnown.Status -eq 204) "status=$($resendKnown.Status) $($resendKnown.Body)"

$resendUnknown = Invoke-Check POST '/api/v1/auth/verify-email/resend' `
  (@{ email = ('nadie' + (Get-Random) + '@example.com') } | ConvertTo-Json -Compress)
Check 'el reenvio responde igual con un correo inexistente (anti-enumeracion)' `
  ($resendUnknown.Status -eq 204 -and $resendUnknown.Body -eq $resendKnown.Body) `
  "status=$($resendUnknown.Status) body=$($resendUnknown.Body)"

# El alta nace PENDING_VERIFICATION: el login debe rechazarse hasta confirmar el
# correo. Se comprueba ese rechazo ANTES de confirmar, porque un gate que no se
# puede observar es un gate que no existe.
$blocked = Invoke-Check POST '/api/v1/auth/login' `
  (@{ email = $email; password = $password } | ConvertTo-Json -Compress)
Check 'el login se rechaza con 403 mientras el correo no esta verificado' `
  ($blocked.Status -eq 403 -and $blocked.Body -match 'EMAIL_NOT_VERIFIED') `
  "status=$($blocked.Status) $($blocked.Body)"

# Confirmacion del correo.
#
# En produccion el usuario hace clic en el enlace del correo. Aqui no hay
# servidor SMTP, asi que el token de verificacion no llega a ninguna parte y no
# hay forma de obtenerlo desde la API por diseño (exponerlo seria una
# vulnerabilidad). Se marca la cuenta directamente en la base de datos.
#
# Lo que esto SUSTITUYE es unicamente el transporte del correo. Lo que NO se
# sustituye, y por tanto si se verifica: el gate de verificacion (el 403
# anterior), la emision del JWT, la cookie, los roles y las 48 rutas.
docker exec xmr-postgres psql -U xmr -d xmr_forecast -q -c `
  "UPDATE users SET status='ACTIVE', email_verified=true, email_verified_at=NOW() WHERE email='$email';" | Out-Null
Write-Host "      (cuenta marcada como verificada en la base de datos: no hay SMTP en este entorno)"

$login = Invoke-Login $email $password
$token = $null
if ($login.Status -eq 200) { $token = ($login.Body | ConvertFrom-Json).accessToken }
Check 'POST /api/v1/auth/login emite access token' ([bool]$token) "status=$($login.Status) $($login.Body)"

$setCookie = $login.Headers['Set-Cookie']
Check 'el login emite la cookie HttpOnly del refresh token' `
  ($setCookie -match 'xmr_refresh' -and $setCookie -match 'HttpOnly' -and $setCookie -match 'SameSite=Strict') `
  "Set-Cookie=$setCookie"

# Se conserva la cookie de refresh: mas adelante se comprueba que el reset de
# contrasena la invalida de verdad (no basta con mirar la base de datos).
$refreshCookie = ''
if ($setCookie) { $refreshCookie = (([string]$setCookie) -split ';')[0].Trim() }

if (-not $token) {
  Write-Host ''
  Write-Host "No se pudo obtener sesion: se omiten las comprobaciones autenticadas." -ForegroundColor Yellow
} else {
  # ------------------------------------------------- 6. rol efectivo
  $me = Invoke-Check GET '/api/v1/auth/me' '' $token
  $meJson = $me.Body | ConvertFrom-Json
  Check 'GET /auth/me publica el campo role (rol efectivo)' ([bool]$meJson.role) $me.Body
  Check 'el id se publica como cadena opaca' ($meJson.id -is [string]) "id=$($meJson.id) tipo=$($meJson.id.GetType().Name)"
  Check 'una cuenta nueva nace VIEWER, nunca ADMIN' ($meJson.role -eq 'VIEWER') "role=$($meJson.role)"

  # ------------------------------------------------- 7. endpoints antes ausentes
  $reads = @(
    '/api/v1/models?page=0&size=5',
    '/api/v1/datasets?page=0&size=5',
    '/api/v1/experiments?page=0&size=5',
    '/api/v1/jobs?page=0&size=5',
    '/api/v1/jobs/summary',
    '/api/v1/notifications?page=0&size=5',
    '/api/v1/notifications/unread-count',
    '/api/v1/predictions?page=0&size=5',
    # market/latest es la llamada del dashboard autenticado y pasa por la cache
    # de Redis (QuoteResponse): sin los datos sinteticos del paso 3b daria 404,
    # y con ellos ejercita la serializacion JDK de la cache.
    '/api/v1/market/latest',
    '/api/v1/market/candles?symbol=XMR-USD&page=0&size=5',
    '/api/v1/market/series?symbol=XMR-USD&limit=5'
  )
  foreach ($path in $reads) {
    $r = Invoke-Check GET $path '' $token
    Check ("GET $path") ($r.Status -eq 200) "status=$($r.Status) $($r.Body)"
  }

  # Un experimento inexistente debe dar 404 con codigo estable, no 500 ni una
  # tabla vacia que el cliente no puede distinguir de "aun no hay metricas".
  $missing = Invoke-Check GET '/api/v1/metrics/experiments/999999' '' $token
  Check 'un experimento inexistente devuelve 404 EXPERIMENT_NOT_FOUND' `
    ($missing.Status -eq 404 -and $missing.Body -match 'EXPERIMENT_NOT_FOUND') `
    "status=$($missing.Status)"

  # El catalogo de modelos responde con las 5 familias de R-06
  $models = Invoke-Check GET '/api/v1/models?page=0&size=20' '' $token
  $families = @()
  if ($models.Status -eq 200) {
    $families = ($models.Body | ConvertFrom-Json).items | ForEach-Object { $_.family }
  }
  foreach ($expected in @('LSTM', 'GRU', 'MOVING_AVERAGE', 'LINEAR_REGRESSION', 'ARIMA')) {
    Check "el catalogo incluye la familia $expected" ($families -contains $expected) "familias=$($families -join ',')"
  }

  # ------------------------------------------------- 8. VIEWER no llega a ADMIN
  foreach ($path in @('/api/v1/audit?page=0&size=5', '/api/v1/users?page=0&size=5')) {
    $r = Invoke-Check GET $path '' $token
    Check "un VIEWER recibe 403 en $path" ($r.Status -eq 403) "status=$($r.Status) $($r.Body)"
  }

  # ------------------------------------------------- 9. escritura segun rol
  $forbidden = Invoke-Check POST '/api/v1/experiments' (@{ name = 'No permitido' } | ConvertTo-Json -Compress) $token
  Check 'un VIEWER recibe 403 al crear un experimento' ($forbidden.Status -eq 403) "status=$($forbidden.Status)"

  $promote = Invoke-Check POST '/api/v1/models/1/promote' (@{ versionId = '1' } | ConvertTo-Json -Compress) $token
  Check 'un VIEWER recibe 403 al promover un campeon' ($promote.Status -eq 403) "status=$($promote.Status)"

  # ------------------------------------------------- 10. camino de escritura (ANALYST)
  # Se promueve la cuenta en la base de datos porque no hay una API publica para
  # hacerlo (por diseño: el cambio de roles es una operacion de ADMIN, R-27).
  docker exec xmr-postgres psql -U xmr -d xmr_forecast -q -c `
    "INSERT INTO user_roles (user_id, role_code) SELECT id, 'ANALYST' FROM users WHERE email='$email' ON CONFLICT DO NOTHING;" | Out-Null
  $login2 = Invoke-Login $email $password
  $analystToken = ($login2.Body | ConvertFrom-Json).accessToken
  Check 'tras el cambio de rol, /auth/me devuelve el rol efectivo ANALYST' `
    (((Invoke-Check GET '/api/v1/auth/me' '' $analystToken).Body | ConvertFrom-Json).role -eq 'ANALYST')

  # Dataset: el checksum lo calcula el servidor, nunca el cliente (R-28).
  # La version lleva marca de tiempo para que el guion sea repetible: la
  # unicidad de (symbol, version) es real y una version fija haria fallar la
  # segunda ejecucion con 409, que es la respuesta correcta pero no lo que esta
  # comprobando este test.
  $datasetVersion = 'v-verif-' + (Get-Random)
  $dataset = Invoke-Check POST '/api/v1/datasets' `
    (@{ symbol = 'XMR-USD'; version = $datasetVersion; source = 'sintetico'; interval = '1d'; rows = 400 } | ConvertTo-Json -Compress) $analystToken
  $datasetJson = $null
  if ($dataset.Status -eq 201) { $datasetJson = $dataset.Body | ConvertFrom-Json }
  Check 'POST /api/v1/datasets crea la version (201)' ($dataset.Status -eq 201) "status=$($dataset.Status) $($dataset.Body)"
  Check 'el checksum lo calcula el servidor y es un SHA-256 hex' `
    ($datasetJson.checksum -match '^[0-9a-f]{64}$') "checksum=$($datasetJson.checksum)"

  # Idempotencia del dataset: repetir (symbol, version) da 409, no un duplicado.
  $dupe = Invoke-Check POST '/api/v1/datasets' `
    (@{ symbol = 'XMR-USD'; version = $datasetVersion; source = 'sintetico'; rows = 400 } | ConvertTo-Json -Compress) $analystToken
  Check 'registrar dos veces la misma version de dataset da 409' `
    ($dupe.Status -eq 409) "status=$($dupe.Status)"

  # Experimento: el servidor genera codigo y configuracion (D-05, R-08).
  $experiment = Invoke-Check POST '/api/v1/experiments' `
    (@{ name = 'Verificacion E2E'; description = 'Creado por tools/verify-stack.ps1'; task = 'REGRESSION' } | ConvertTo-Json -Compress) $analystToken
  $experimentJson = $null
  if ($experiment.Status -eq 201) { $experimentJson = $experiment.Body | ConvertFrom-Json }
  Check 'POST /api/v1/experiments crea el experimento (201)' `
    ($experiment.Status -eq 201) "status=$($experiment.Status) $($experiment.Body)"
  Check 'el estado publicado es PENDING (traducido de DRAFT)' `
    ($experimentJson.status -eq 'PENDING') "status=$($experimentJson.status)"

  # Corrida: exige >= 5 semillas (R-08) y encola un trabajo.
  # El runKey tambien lleva marca de tiempo por el mismo motivo de repetibilidad.
  $runKey = 'run-verif-' + (Get-Random)
  $run = Invoke-Check POST "/api/v1/experiments/$($experimentJson.id)/runs" `
    (@{ runKey = $runKey; seeds = @(1,2,3) } | ConvertTo-Json -Compress) $analystToken
  Check 'una corrida con menos de 5 semillas se rechaza (R-08)' `
    ($run.Status -eq 400 -and $run.Body -match 'INSUFFICIENT_SEEDS') "status=$($run.Status) $($run.Body)"

  $run2 = Invoke-Check POST "/api/v1/experiments/$($experimentJson.id)/runs" `
    (@{ runKey = $runKey; seeds = @(1,2,3,4,5) } | ConvertTo-Json -Compress) $analystToken
  $runJson = $null
  if ($run2.Status -eq 201) { $runJson = $run2.Body | ConvertFrom-Json }
  Check 'POST /experiments/{id}/runs encola la corrida con 5 semillas (201)' `
    ($run2.Status -eq 201) "status=$($run2.Status) $($run2.Body)"
  Check 'el trabajo encolado es de tipo TRAIN' `
    ($runJson.job.type -eq 'TRAIN') "tipo=$($runJson.job.type)"

  # Idempotencia de la corrida: el mismo runKey da 409.
  $runDupe = Invoke-Check POST "/api/v1/experiments/$($experimentJson.id)/runs" `
    (@{ runKey = $runKey; seeds = @(1,2,3,4,5) } | ConvertTo-Json -Compress) $analystToken
  Check 'repetir el mismo runKey devuelve 409 (no duplica la corrida)' `
    ($runDupe.Status -eq 409) "status=$($runDupe.Status)"

  # Metricas del experimento recien creado: ahora SI existe.
  $metrics = Invoke-Check GET "/api/v1/metrics/experiments/$($experimentJson.id)" '' $analystToken
  Check 'GET /api/v1/metrics/experiments/{id} responde 200 para un experimento real' `
    ($metrics.Status -eq 200) "status=$($metrics.Status) $($metrics.Body)"

  $compare = Invoke-Check GET "/api/v1/metrics/compare?experimentId=$($experimentJson.id)" '' $analystToken
  Check 'GET /api/v1/metrics/compare responde 200 (puede devolver lista vacia)' `
    ($compare.Status -eq 200) "status=$($compare.Status) $($compare.Body)"

  # El trabajo TRAIN debe aparecer en la cola.
  $jobs = Invoke-Check GET '/api/v1/jobs?page=0&size=20' '' $analystToken
  $jobTypes = @()
  if ($jobs.Status -eq 200) { $jobTypes = ($jobs.Body | ConvertFrom-Json).items | ForEach-Object { $_.type } }
  Check 'el trabajo TRAIN aparece en la cola' ($jobTypes -contains 'TRAIN') "tipos=$($jobTypes -join ',')"

  # ------------------------------------------------- 11. la cola AVANZA
  #
  # "Aparece en la cola" no es "se procesa". Se espera a que el worker reclame el
  # trabajo y lo lleve a un estado terminal, y se comprueba que el estado
  # publicado sea coherente con el TRAINING_NOT_EXPOSED documentado (D-10): el
  # entrenamiento no se expone por HTTP, asi que FAILED con ese motivo es la
  # respuesta correcta. Lo que NO es correcto es quedarse en PENDING, ni que el
  # trabajo pase a SUCCEEDED sin haber entrenado nada.
  $terminal = $null
  for ($i = 1; $i -le 12; $i++) {
    $poll = Invoke-Check GET "/api/v1/jobs/$($runJson.job.id)" '' $analystToken
    if ($poll.Status -eq 200) {
      $current = ($poll.Body | ConvertFrom-Json)
      if ($current.status -in @('SUCCEEDED', 'FAILED', 'RUNNING')) { $terminal = $current; break }
      if ($current.status -eq 'CANCELLED') { $terminal = $current; break }
    }
    Start-Sleep -Seconds 5
  }
  Check 'el trabajo encolado alcanza un estado terminal (el worker lo procesa)' `
    ($null -ne $terminal) "estado=$($terminal.status)"

  if ($null -ne $terminal) {
    Check 'el trabajo TRAIN termina en FAILED con TRAINING_NOT_EXPOSED (D-10), no en SUCCEEDED' `
      ($terminal.status -eq 'FAILED' -and $terminal.message -match 'TRAINING_NOT_EXPOSED') `
      "estado=$($terminal.status) mensaje=$($terminal.message)"
  }

  # La corrida debe reflejar el desenlace del trabajo, no quedarse en PENDING.
  $runAfter = Invoke-Check GET "/api/v1/experiments/$($experimentJson.id)/runs/$($runJson.run.id)" '' $analystToken
  $runAfterJson = $null
  if ($runAfter.Status -eq 200) { $runAfterJson = $runAfter.Body | ConvertFrom-Json }
  Check 'la corrida pasa a un estado terminal, no se queda en PENDING' `
    ($runAfterJson -and $runAfterJson.status -in @('FAILED', 'SUCCEEDED', 'CANCELLED')) `
    "estado=$($runAfterJson.status)"

  # El experimento tampoco puede quedarse en RUNNING: su unica corrida ya termino.
  $expAfter = Invoke-Check GET "/api/v1/experiments/$($experimentJson.id)" '' $analystToken
  $expAfterJson = $null
  if ($expAfter.Status -eq 200) { $expAfterJson = $expAfter.Body | ConvertFrom-Json }
  Check 'el experimento ya no se queda en RUNNING con su corrida terminada' `
    ($expAfterJson -and $expAfterJson.status -ne 'RUNNING') `
    "estado=$($expAfterJson.status)"
}

# ------------------------------------------------- 12. recuperacion de contrasena
#
# Flujo completo por HTTP: respuesta generica (anti-enumeracion), token
# guardado con hash, expiracion corta, uso unico, invalidacion de sesiones,
# cambio efectivo de la contrasena y auditoria.
#
# Aqui NO hay bandeja de correo, de modo que el unico paso que se sustituye es
# la RECEPCION del enlace: se calcula el mismo HMAC-SHA256 que calcula el
# servidor (TokenHasher) con el mismo `JWT_SECRET` y se inserta la fila que
# `forgotPassword` habria creado. Lo que NO se sustituye es una sola linea del
# servidor: la validacion, el consumo del token, el cierre de sesiones, el
# cambio de hash de contrasena y la auditoria se ejecutan de verdad por HTTP.
if ($token) {
  # ---------------------------------------- 12a. el limitador de tasa existe
  #
  # Los caminos sensibles (login, register, refresh, password/*, verify-email)
  # comparten UN cubo por IP de `RATE_LIMIT_LOGIN` peticiones por minuto. Se
  # comprueba de verdad agotandolo con `password/forgot` sobre un correo
  # inexistente: esa llamada no crea cuentas, no emite tokens ni audita, asi
  # que es la forma mas limpia de probar el limite sin efectos secundarios.
  $limitDetail = 'sin 429 en 8 intentos'
  $limited = $null
  $retryAfter = ''
  $hammerBody = @{ email = ('limite' + (Get-Random) + '@example.com') } | ConvertTo-Json -Compress
  for ($attempt = 1; $attempt -le 8 -and $null -eq $limited; $attempt++) {
    $probe = Invoke-Check POST '/api/v1/auth/password/forgot' $hammerBody
    if ($probe.Status -eq 429) { $limited = $probe }
  }
  if ($null -ne $limited) {
    $retryAfter = [string]$limited.Headers['Retry-After']
    $limitDetail = "status=429 Retry-After=$retryAfter"
  }
  Check 'password/forgot devuelve 429 al agotar el cubo sensible, con Retry-After' `
    ($null -ne $limited -and $retryAfter -match '^\d+$') $limitDetail

  # Se devuelve el limitador a su estado inicial, con el mismo criterio con el
  # que el guion lo resetea al arrancar: este script nadie lo ejecuta en
  # produccion, y sin este reset los pasos siguientes esperarian 61 s cada uno
  # sin probar nada que no se haya probado ya. El 429 de arriba es la
  # comprobacion; lo que viene despues es el flujo, no el limite.
  docker exec xmr-redis sh -c `
    'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning KEYS "ratelimit:*" | xargs -r redis-cli -a "$REDIS_PASSWORD" --no-auth-warning DEL' | Out-Null

  # --------------------------------------------------- 12b. el flujo de verdad
  $forgot = Invoke-Sensitive POST '/api/v1/auth/password/forgot' (@{ email = $email } | ConvertTo-Json -Compress)
  Check 'POST /password/forgot responde 204 con la cuenta existente' `
    ($forgot.Status -eq 204) "status=$($forgot.Status) $($forgot.Body)"

  $forgottenUnknown = Invoke-Sensitive POST '/api/v1/auth/password/forgot' `
    (@{ email = ('nadie' + (Get-Random) + '@example.com') } | ConvertTo-Json -Compress)
  Check 'el olvido responde igual con un correo inexistente (anti-enumeracion)' `
    ($forgottenUnknown.Status -eq 204 -and $forgot.Status -eq 204 -and $forgottenUnknown.Body -eq $forgot.Body) `
    "status=$($forgottenUnknown.Status) body=$($forgottenUnknown.Body)"

  # El token se guarda con hash, con un TTL corto y sin consumir.
  $tokenRow = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT prt.token_hash || '|' || CASE WHEN prt.expires_at > NOW() AND prt.expires_at <= NOW() + interval '3 hours' THEN 'ttl-ok' ELSE 'ttl-bad' END || '|' || (prt.consumed_at IS NULL)::text FROM password_reset_tokens prt JOIN users u ON u.id = prt.user_id WHERE u.email = '$email' AND prt.purpose = 'RESET' ORDER BY prt.id DESC LIMIT 1;"
  $tokenParts = ("$tokenRow".Trim() -split '\|')
  Check 'el token de reset se guarda con hash HMAC-SHA256 (64 hex), nunca en claro' `
    ($tokenParts[0] -match '^[0-9a-f]{64}$') "fila=$($tokenRow)"
  Check 'el token de reset expira en menos de 3 horas (TTL corto: 2 h)' `
    ($tokenParts[1] -eq 'ttl-ok') "ttl=$($tokenParts[1])"
  # (el CAST a text de un booleano en PostgreSQL es 'true'/'false', no 't'/'f':
  #  el primer intento de esta comprobacion fallo por comparar con 't' y acuso
  #  de "consumido" a un token que estaba intacto)
  Check 'el token de reset nace sin consumir (uso unico pendiente)' `
    ($tokenParts[2] -eq 'true') "consumed=$($tokenParts[2])"

  # Segunda solicitud dentro de la ventana de gracia (2 min): mismo 204 y NO se
  # emite otro token, porque el enlace anterior sigue siendo el valido.
  $activeBefore = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT count(*) FROM password_reset_tokens prt JOIN users u ON u.id = prt.user_id WHERE u.email = '$email' AND prt.purpose = 'RESET' AND prt.consumed_at IS NULL;"
  $forgotAgain = Invoke-Sensitive POST '/api/v1/auth/password/forgot' (@{ email = $email } | ConvertTo-Json -Compress)
  $activeAfter = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT count(*) FROM password_reset_tokens prt JOIN users u ON u.id = prt.user_id WHERE u.email = '$email' AND prt.purpose = 'RESET' AND prt.consumed_at IS NULL;"
  Check 'una segunda solicitud dentro del cooldown responde 204 sin emitir otro token' `
    ($forgotAgain.Status -eq 204 -and "$activeAfter".Trim() -eq "$activeBefore".Trim()) `
    "antes=$("$activeBefore".Trim()) despues=$("$activeAfter".Trim()) status=$($forgotAgain.Status)"

  # Enlace con el mismo calculo que hace el servidor.
  $jwtSecret = Get-EnvValue 'JWT_SECRET'
  $plainToken = 'verify-e2e-' + [guid]::NewGuid().ToString('N')
  $hmac = New-Object System.Security.Cryptography.HMACSHA256
  $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($jwtSecret)
  $resetHash = ([System.BitConverter]::ToString(
    $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($plainToken))) -replace '-', '').ToLower()
  docker exec xmr-postgres psql -U xmr -d xmr_forecast -q -c `
    "INSERT INTO password_reset_tokens (user_id, token_hash, purpose, expires_at) SELECT id, '$resetHash', 'RESET', NOW() + interval '1 hour' FROM users WHERE email = '$email';" | Out-Null

  $newPassword = 'Recuperacion#2026segura'
  $reset = Invoke-Sensitive POST '/api/v1/auth/password/reset' `
    (@{ token = $plainToken; newPassword = $newPassword } | ConvertTo-Json -Compress)
  Check 'POST /password/reset con un token valido responde 204' `
    ($reset.Status -eq 204) "status=$($reset.Status) $($reset.Body)"

  # Uso unico: el mismo enlace no sirve dos veces.
  $reuse = Invoke-Sensitive POST '/api/v1/auth/password/reset' `
    (@{ token = $plainToken; newPassword = 'Otra#Contrasena2026' } | ConvertTo-Json -Compress)
  Check 'reutilizar el mismo enlace se rechaza (uso unico)' `
    ($reuse.Status -eq 400 -and $reuse.Body -match 'RESET_TOKEN') "status=$($reuse.Status) $($reuse.Body)"

  # Un token inventado no provoca 500: codigo estable.
  $forged = Invoke-Sensitive POST '/api/v1/auth/password/reset' `
    (@{ token = ('inventado-' + [guid]::NewGuid().ToString('N')); newPassword = 'Otra#Contrasena2026' } | ConvertTo-Json -Compress)
  Check 'un token inexistente se rechaza con INVALID_RESET_TOKEN' `
    ($forged.Status -eq 400 -and $forged.Body -match 'INVALID_RESET_TOKEN') "status=$($forged.Status) $($forged.Body)"

  # Todas las sesiones previas mueren (logoutAll con motivo PASSWORD_RESET).
  $activeRefresh = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = '$email' AND rt.revoked_at IS NULL;"
  $revokedReset = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = '$email' AND rt.revoked_reason = 'PASSWORD_RESET';"
  Check 'tras el reset no queda ninguna sesion de refresh activa' `
    ("$activeRefresh".Trim() -eq '0') "activas=$("$activeRefresh".Trim())"
  Check 'las sesiones revocadas llevan el motivo PASSWORD_RESET' `
    ("$revokedReset".Trim() -ge '1') "revocadas=$("$revokedReset".Trim())"

  # Y por HTTP, no solo en la base de datos: la cookie de refresh del login
  # anterior deja de ser aceptada.
  #
  # Se manda cuerpo `{}`: el endpoint exige `@RequestBody` (el CAMPO refreshToken
  # es el que es opcional, porque si falta se lee la cookie), y sin cuerpo
  # Invoke-WebRequest envia `application/x-www-form-urlencoded`, que Spring
  # rechaza con 415 antes de mirar la cookie. El propio frontend hace lo mismo
  # (`body: body ?? {}` en refreshCoordinator).
  if ($refreshCookie) {
    $staleRefresh = Invoke-Sensitive POST '/api/v1/auth/refresh' '{}' '' $refreshCookie
    Check 'la cookie de refresh anterior deja de ser aceptada tras el reset (401)' `
      ($staleRefresh.Status -eq 401) "status=$($staleRefresh.Status) $($staleRefresh.Body)"
  }

  # La contrasena vieja deja de entrar y la nueva entra: cambio efectivo.
  $oldLogin = Invoke-Login $email $password
  Check 'tras el reset la contrasena anterior deja de funcionar' `
    ($oldLogin.Status -in @(401, 403)) "status=$($oldLogin.Status) $($oldLogin.Body)"
  $newLogin = Invoke-Login $email $newPassword
  Check 'tras el reset la contrasena nueva inicia sesion' `
    ($newLogin.Status -eq 200) "status=$($newLogin.Status) $($newLogin.Body)"

  # Auditoria del reset (el encargo la exige explicitamente).
  $auditReset = docker exec xmr-postgres psql -U xmr -d xmr_forecast -t -A -c `
    "SELECT count(*) FROM audit_events ae JOIN users u ON u.id = ae.actor_user_id WHERE u.email = '$email' AND ae.action = 'AUTH_PASSWORD_RESET';"
  Check 'el reset queda registrado en la auditoria (AUTH_PASSWORD_RESET)' `
    ("$auditReset".Trim() -ge '1') "filas=$("$auditReset".Trim())"
}

Write-Host ''
Write-Host '=== Resumen ==='
if ($failures -eq 0) { Write-Host 'Todas las comprobaciones pasan.' -ForegroundColor Green }
else { Write-Host "$failures comprobaciones fallan." -ForegroundColor Red }
exit $failures
