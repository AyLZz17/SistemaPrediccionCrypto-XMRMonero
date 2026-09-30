// =====================================================================
// XMR-Forecast :: prueba de carga con k6
//
// Uso:
//   k6 run -e BASE_URL=https://localhost:8443 \
//          -e VUS=100 -e DURATION=1m \
//          loadtests/api.js
//
// Requisitos: el sistema debe estar levantado (docker compose up -d) y el
// k6 debe confiar en la CA de desarrollo, o bien ejecutarse con
// --insecure-skip-tls-verify (solo en desarrollo).
//
// IMPORTANTE (seccion 15): este script NO afirma ninguna capacidad de
// carga. Los resultados se registran ejecutandolo y volcando el summary a
// docs/07_pruebas_carga.md. Si no se ha ejecutado, no hay cifras.
// =====================================================================
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------- metrics
const loginErrors = new Counter('xmr_login_errors');
const rateLimited = new Counter('xmr_rate_limited');
const authErrors = new Counter('xmr_auth_errors');
const marketLatency = new Trend('xmr_market_latency', true);
const predictionLatency = new Trend('xmr_prediction_latency', true);
const metricsLatency = new Trend('xmr_metrics_latency', true);
const authzDenials = new Counter('xmr_authz_denials');

// ---------------------------------------------------------------- options
const BASE = __ENV.BASE_URL || 'https://localhost:8443';
const API = `${BASE}/api/v1`;
const IGNORE_TLS = (__ENV.IGNORE_TLS || 'false') === 'true';
const CREW = Number(__ENV.CREW_SIZE || 20);

export const options = {
  // Etapas: sostenida -> pico -> estres. Los niveles pedidos son 100/1000/3000
  // usuarios; se parametrizan con VUS para no fijarlos en el codigo.
  stages: [
    { duration: __ENV.WARMUP || '1m', target: Number(__ENV.VUS || 100) },
    { duration: __ENV.DURATION || '3m', target: Number(__ENV.VUS || 100) },
    { duration: __ENV.SPIKE || '30s', target: Number(__ENV.SPIKE_VUS || 200) },
    { duration: __ENV.RECOVERY || '1m', target: Number(__ENV.VUS || 100) },
  ],
  thresholds: {
    // Umbrales de referencia. Se ajustan con datos reales, no al revés.
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    checks: ['rate>0.95'],
  },
  // El login es costoso (BCrypt coste 12): se limita para no medir solo el hash.
  noConnectionReuse: false,
};

// -------------------------------------------------------------- helpers
function commonHeaders(token) {
  const headers = {
    'Content-Type': 'application/json',
    'X-Request-Id': `k6-${__VU}-${__ITER}`,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

function seedEmails() {
  // Un conjunto pequeno de cuentas: la prueba mide el servicio, no un
  // registro masivo de usuarios. Ampliarlo exige coordination previa.
  const emails = [];
  for (let i = 0; i < CREW; i++) emails.push(`loadtest${i}@example.com`);
  return emails;
}

const EMAILS = seedEmails();

// ------------------------------------------------------------ scenarios
export default function () {
  const email = EMAILS[__VU % EMAILS.length];
  let token = null;

  // ---------------------------------------------------------- 1. login
  group('01 Login', () => {
    const res = http.post(
      `${API}/auth/login`,
      JSON.stringify({ email, password: 'CargaDePrueba123!' }),
      { headers: commonHeaders(), insecureSkipTLSVerify: IGNORE_TLS },
    );

    if (res.status === 429) {
      rateLimited.add(1);
    } else if (res.status !== 200) {
      loginErrors.add(1);
    }

    const ok = check(res, {
      'login responde 200': (r) => r.status === 200,
      'login devuelve accessToken': (r) => !!(r.json() || {}).accessToken,
      'login nunca devuelve password': (r) => !JSON.stringify(r.json() || {}).match(/password/i),
    });

    if (ok) token = res.json('accessToken');
    sleep(0.5);
  });

  // ------------------------------------------------- 2. authorization
  group('02 Autorizacion concurrente', () => {
    // Un VIEWER NO debe poder promover un campeon (OWASP API5 / IDOR).
    const res = http.post(
      `${API}/models/1/promote`,
      JSON.stringify({}),
      { headers: commonHeaders(token), insecureSkipTLSVerify: IGNORE_TLS },
    );
    if (res.status === 403) authzDenials.add(1);
    check(res, {
      'no permite promover sin ADMIN': (r) => r.status === 403 || r.status === 401 || r.status === 404,
    });
  });

  // --------------------------------------------------------- 3. mercado
  group('03 Mercado', () => {
    const res = http.get(`${API}/market/latest?symbol=XMR-USD`, {
      headers: commonHeaders(token),
      insecureSkipTLSVerify: IGNORE_TLS,
    });
    marketLatency.add(res.timings.duration);
    check(res, { 'mercado responde 200': (r) => r.status === 200 });

    const candles = http.get(`${API}/market/candles?symbol=XMR-USD&page=0&size=50`, {
      headers: commonHeaders(token),
      insecureSkipTLSVerify: IGNORE_TLS,
    });
    check(candles, { 'velas responden 200': (r) => r.status === 200 });
  });

  // ------------------------------------------------------ 4. predicciones
  group('04 Predicciones', () => {
    const res = http.get(`${API}/predictions?page=0&size=20`, {
      headers: commonHeaders(token),
      insecureSkipTLSVerify: IGNORE_TLS,
    });
    predictionLatency.add(res.timings.duration);
    if (res.status === 401 || res.status === 403) authErrors.add(1);
    check(res, { 'predicciones responden 200': (r) => r.status === 200 });
  });

  // --------------------------------------------------------- 5. metricas
  group('05 Metricas', () => {
    const res = http.get(`${API}/metrics/experiments/1`, {
      headers: commonHeaders(token),
      insecureSkipTLSVerify: IGNORE_TLS,
    });
    metricsLatency.add(res.timings.duration);
    check(res, { 'metricas responden 200 o 404': (r) => r.status === 200 || r.status === 404 });
  });

  // ----------------------------------------------------- 6. experimentos
  group('06 Experimentos', () => {
    const res = http.get(`${API}/experiments?page=0&size=20`, {
      headers: commonHeaders(token),
      insecureSkipTLSVerify: IGNORE_TLS,
    });
    check(res, { 'experimentos responden 200': (r) => r.status === 200 });
  });

  sleep(1);
}

// ---------------------------------------------------------------- summary
export function handleSummary(data) {
  const out = JSON.stringify(
    {
     vus: data.metrics.vus?.values?.max,
      iterations: data.metrics.iterations?.values?.count,
      http_req_duration_p50: data.metrics.http_req_duration?.values?.['p(50)'],
      http_req_duration_p95: data.metrics.http_req_duration?.values?.['p(95)'],
      http_req_duration_p99: data.metrics.http_req_duration?.values?.['p(99)'],
      http_req_failed_rate: data.metrics.http_req_failed?.values?.rate,
      checks_rate: data.metrics.checks?.values?.rate,
      status_counts: data.metrics.http_req_failed
        ? data.root_group?.checks
        : undefined,
    },
    null,
    2,
  );
  return {
    stdout: `${out}\n`,
    'loadtests/summary.json': out,
  };
}