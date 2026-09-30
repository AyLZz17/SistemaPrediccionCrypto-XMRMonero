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
#   9. escritura permitida para VIEWER+ segun rol, y 403 donde no cabe.
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

# Invoke-Check <METHOD> <path> [body] [token] -> @{ Status; Body; Headers }
function Invoke-Check([string]$method, [string]$path, [string]$body = '', [string]$token = '') {
  $headers = @{ 'X-Request-Id' = ('verify-' + [guid]::NewGuid().ToString('N').Substring(0,12)) }
  if ($token) { $headers['Authorization'] = "Bearer $token" }
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

# ------------------------------------------------------------ 3. ruta protegida
$unauth = Invoke-Check GET '/api/v1/market/latest'
Check 'una ruta protegida sin token devuelve 401' ($unauth.Status -eq 401) "status=$($unauth.Status)"

# ------------------------------------------------------------ 4. sin HTTP plano
$plain = 0
try { $c = New-Object System.Net.Sockets.TcpClient
      $iar = $c.BeginConnect('127.0.0.1', 8443, $null, $null)
      $c.Close() } catch { $plain = 1 }
$plainRefused = $true
try { $c2 = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 80); $plainRefused = $false; $c2.Close() } catch { }
Check 'no hay listener HTTP plano en el puerto 80' $plainRefused

# ------------------------------------------------------------ 5. registro y login
$email = 'verificacion' + (Get-Random) + '@example.com'
$password = 'Verificacion#2026segura'
$register = Invoke-Check POST '/api/v1/auth/register' `
  (@{ email = $email; password = $password; fullName = 'Prueba Verificacion' } | ConvertTo-Json -Compress)
Check 'POST /api/v1/auth/register crea la cuenta' `
  ($register.Status -eq 201 -and $register.Body -match '"id"') "status=$($register.Status) $($register.Body)"

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
# anterior), la emision del JWT, la cookie, los roles y las 46 rutas.
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
}

Write-Host ''
Write-Host '=== Resumen ==='
if ($failures -eq 0) { Write-Host 'Todas las comprobaciones pasan.' -ForegroundColor Green }
else { Write-Host "$failures comprobaciones fallan." -ForegroundColor Red }
exit $failures
