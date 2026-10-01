package com.aylzz.xmrforecast.ml;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
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
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final AppProperties properties;
    private final RestClient restClient;

    public MlServiceClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.ml().baseUrl())
                .requestFactory(requestFactory(properties.ml()))
                .build();
        // Falla el arranque, no la primera prediccion en produccion.
        assertSecureBaseUrl();
    }

    /**
     * Fabrica de conexiones con TLS verificado.
     *
     * <p>La verificacion no se desactiva nunca. Para el certificado autofirmado de
     * desarrollo se anade la CA al almacen del proceso
     * ({@code -Djavax.net.ssl.trustStore}, ver {@code JAVA_OPTS} en
     * {@code docker-compose.yml}); no existe una bandera que lo omita, porque una
     * bandera de ese tipo acabaria activada en algun entorno por descuido.
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
        int attempts = properties.ml().maxRetries() + 1;
        RuntimeException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String body = "GET".equals(method) ? get(path, payload) : post(path, payload);
                return parse(body);
            } catch (ResourceAccessException ex) {
                // Fallo de transporte: el servicio esta caido o el timeout se disparo.
                last = ex;
                log.warn("Fallo de transporte hacia el servicio ML (intento {}/{}): {}",
                        attempt, attempts, ex.getMessage());
            } catch (HttpStatusCodeException ex) {
                // El servicio responde, con error. Un 5xx es transitorio y merece
                // otro intento; un 4xx no lo es. Antes de distinguir, ambos
                // llegaban al cliente como 500 con la traza del servicio remoto.
                int status = ex.getStatusCode().value();
                last = ex;
                if (status >= 500) {
                    log.warn("El servicio ML devolvio {} (intento {}/{})", status, attempt, attempts);
                } else {
                    throw ApiException.badGateway("ML_SERVICE_REJECTED",
                            "El servicio de Machine Learning rechazo la peticion.");
                }
            } catch (ApiException ex) {
                // Error ya traducido por esta misma clase: reintentar no aporta.
                throw ex;
            }

            if (attempt < attempts) {
                sleepBackoff(attempt);
            }
        }

        log.warn("El servicio ML no respondio tras {} intentos: {}",
                attempts, last == null ? "sin detalle" : last.getMessage());
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
        // Secreto compartido con el servicio ML. Solo se envia si esta
        // configurado (en local va vacio y el ML no lo exige).
        internalTokenHeader(properties.ml()).ifPresent(token ->
                headers.set(INTERNAL_TOKEN_HEADER, token));
    }

    /**
     * Cabecera {@code X-Internal-Token} a enviar.
     *
     * <p>Vacio/ausente significa NO enviarla: mandar una cadena vacia haria que
     * el servicio ML rechazara el 100 % de las llamadas con 401.
     */
    static java.util.Optional<String> internalTokenHeader(AppProperties.Ml ml) {
        String token = ml.internalToken();
        return (token != null && !token.isBlank())
                ? java.util.Optional.of(token)
                : java.util.Optional.empty();
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

    /**
     * Verifica que la URL configurada del servicio ML sea HTTPS (R-32, R-33).
     *
     * <p>Se invoca desde el constructor: un {@code http://} en la configuracion
     * rompe el arranque en lugar de permitir que el primer fallo se descubra en
     * la primera prediccion, en produccion, con datos de un usuario en pantalla.
     */
    public void assertSecureBaseUrl() {
        if (!properties.ml().baseUrl().startsWith("https://")) {
            throw new IllegalStateException(
                    "app.ml.base-url debe usar https:// ; se configuro: " + properties.ml().baseUrl());
        }
    }
}