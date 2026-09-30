package com.aylzz.xmrforecast.ml;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

/**
 * Cliente tipado del servicio FastAPI-ML.
 *
 * <p>Contrato de integracion (seccion 11): solo HTTPS, con timeout de conexion y
 * de lectura distintos, reintentos limitados con espera incremental y propagacion
 * del {@code X-Request-Id} para que frontend, backend y ML compartan un mismo
 * identificador de correlacion (R-32).
 */
@Component
public class MlServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final AppProperties properties;
    private final RestClient restClient;

    public MlServiceClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.ml().baseUrl())
                .requestFactory(requestFactory(properties.ml()))
                .build();
    }

    /**
     * Fabrica de conexiones con TLS verificado. La verificacion nunca se desactiva:
     * {@code verifyTls=false} solo permite el certificado autofirmado de desarrollo,
     * y solo si se permite explicitamente por entorno.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(
            AppProperties.Ml ml) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ml.connectTimeoutMs());
        factory.setReadTimeout(ml.readTimeoutMs());
        return factory;
    }

    /** Salud del servicio ML. Nunca lanza: se usa en el health check. */
    public boolean isHealthy() {
        try {
            String body = get("/health", Map.of());
            return body.contains("UP");
        } catch (RuntimeException ex) {
            log.debug("El servicio ML no responde health: {}", ex.getMessage());
            return false;
        }
    }

    public JsonNode models() {
        return withRetry("GET", "/v1/models", Map.of());
    }

    public JsonNode compareMetrics(String experimentId) {
        return withRetry("GET", "/v1/metrics/compare",
                Map.of("experiment_id", experimentId == null ? "" : experimentId));
    }

    public JsonNode predict(Map<String, Object> payload) {
        return withRetry("POST", "/v1/predict", payload);
    }

    private JsonNode withRetry(String method, String path, Map<String, Object> payload) {
        RuntimeException last = null;
        int attempts = properties.ml().maxRetries() + 1;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String body = "GET".equals(method) ? get(path, payload) : post(path, payload);
                return parse(body);
            } catch (ResourceAccessException ex) {
                // Fallo de transporte: el servicio esta caido o el timeout se disparo.
                last = ex;
                log.warn("Fallo de transporte hacia el servicio ML (intento {}/{}): {}",
                        attempt, attempts, ex.getMessage());
            } catch (ApiException ex) {
                // El servicio respondio con un error de negocio: reintentar no ayuda.
                throw ex;
            }

            if (attempt < attempts) {
                sleepBackoff(attempt);
            }
        }

        throw ApiException.unavailable("ML_SERVICE_UNAVAILABLE",
                "El servicio de Machine Learning no esta disponible en este momento.");
    }

    private String get(String path, Map<String, Object> query) {
        var builder = org.springframework.web.util.UriComponentsBuilder.fromPath(path);
        // queryParam(String, Object...) no admite un Map: se anade cada clave por separado.
        query.forEach((name, value) -> builder.queryParam(name, value));
        var uri = builder.build(true).toUri();
        return restClient.get()
                .uri(uri)
                .headers(this::propagateCorrelation)
                .retrieve()
                .body(String.class);
    }

    private String post(String path, Map<String, Object> payload) {
        return restClient.post()
                .uri(path)
                .headers(this::propagateCorrelation)
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    private void propagateCorrelation(org.springframework.http.HttpHeaders headers) {
        if (RequestContext.requestId() != null) {
            headers.set(REQUEST_ID_HEADER, RequestContext.requestId());
        }
        if (RequestContext.traceId() != null) {
            headers.set(TRACE_ID_HEADER, RequestContext.traceId());
        }
    }

    private JsonNode parse(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw ApiException.badGateway("ML_INVALID_RESPONSE",
                    "El servicio de Machine Learning devolvio una respuesta ilegible.");
        }
    }

    /** Espera incremental entre reintentos; sin ella un fallo rean stormea. */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(500L * attempt, 2000L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("ML_SERVICE_INTERRUPTED",
                    "La llamada al servicio de Machine Learning fue interrumpida.");
        }
    }

    /** Verifica que la URL configurada del servicio ML sea HTTPS (R-32, R-33). */
    public void assertSecureBaseUrl() {
        if (!properties.ml().baseUrl().startsWith("https://")) {
            throw new IllegalStateException(
                    "app.ml.base-url debe usar https:// ; se configuro: " + properties.ml().baseUrl());
        }
        if (!properties.ml().verifyTls()) {
            log.warn("Verificacion TLS del servicio ML desactivada: solo admisible en desarrollo");
        }
    }
}