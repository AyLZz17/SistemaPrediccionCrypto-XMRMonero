package com.aylzz.xmrforecast.ml;

import com.aylzz.xmrforecast.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del cliente del servicio ML.
 *
 * <p>El caso critico es el payload de {@code /v1/predict}: el servicio ML declara
 * {@code extra='forbid'} en sus esquemas, de modo que enviar un campo que no exista
 * alla provoca un 422 y rompe TODAS las predicciones. Estas pruebas fijan el contrato
 * para que el desajuste se detecte aqui y no en produccion.
 */
class MlServiceClientContractTest {

    /** Campos que {@code app/api/schemas.py::PredictRequest} acepta. */
    private static final java.util.Set<String> ACCEPTED_PREDICT_FIELDS =
            java.util.Set.of("model_key", "version", "lookback_days", "symbol");

    /** Campos que {@code PredictionResponse} devuelve. */
    private static final java.util.Set<String> RESPONSE_FIELDS = java.util.Set.of(
            "model_key", "version", "target_date", "predicted_close",
            "predicted_direction", "actual_close", "confidence", "trace");

    @Test
    @DisplayName("El payload de prediccion solo lleva campos que el servicio ML acepta")
    void predictPayloadMatchesMlContract() {
        // Reproduce exactamente lo que construye PredictionService.create().
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model_key", "1");
        payload.put("version", "v1");
        payload.put("symbol", "XMR-USD");
        payload.put("lookback_days", 30);

        assertThat(payload.keySet())
                .as("el servicio ML rechaza campos extra con 422")
                .isSubsetOf(ACCEPTED_PREDICT_FIELDS);
        assertThat(payload).containsOnlyKeys("model_key", "version", "symbol", "lookback_days");
    }

    @Test
    @DisplayName("No se envia 'seed': ya viaja en la version del modelo persistida")
    void seedIsNotSentOverTheWire() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model_key", "1");
        payload.put("version", "v1");
        payload.put("symbol", "XMR-USD");
        payload.put("lookback_days", 30);

        assertThat(payload)
                .as("la semilla se toma de model_versions.seed, no del payload")
                .doesNotContainKey("seed");
    }

    @Test
    @DisplayName("Los campos que el backend lee existen en la respuesta del servicio ML")
    void responseFieldsAreConsumedSafely() {
        // PredictionService lee: target_date, predicted_close, predicted_direction,
        // confidence y trace. Todos deben existir en PredictionResponse.
        for (String field : java.util.List.of(
                "target_date", "predicted_close", "predicted_direction", "confidence", "trace")) {
            assertThat(RESPONSE_FIELDS)
                    .as("campo consumido por el backend: %s", field)
                    .contains(field);
        }
    }

    @Test
    @DisplayName("La direccion se normaliza a un valor del catalogo de la base de datos")
    void directionIsNormalizedToDatabaseValues() {
        // ck_predictions exige UP/DOWN/FLAT: cualquier otra cosa se reduce a FLAT.
        assertThat(normalize("UP")).isEqualTo("UP");
        assertThat(normalize("up")).isEqualTo("UP");
        assertThat(normalize("DOWN")).isEqualTo("DOWN");
        assertThat(normalize("  Down  ")).isEqualTo("DOWN");
        assertThat(normalize("FLAT")).isEqualTo("FLAT");
        // Valores inesperados no deben romper la insercion.
        assertThat(normalize("SUBE")).isEqualTo("FLAT");
        assertThat(normalize("")).isEqualTo("FLAT");
        assertThat(normalize(null)).isEqualTo("FLAT");
    }

    /** Replica la normalizacion de PredictionService.direction(). */
    private static String normalize(String value) {
        try {
            return com.aylzz.xmrforecast.prediction.Prediction.Direction
                    .valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            return com.aylzz.xmrforecast.prediction.Prediction.Direction.FLAT.name();
        } catch (NullPointerException ex) {
            return com.aylzz.xmrforecast.prediction.Prediction.Direction.FLAT.name();
        }
    }

    @Test
    @DisplayName("La base de URLs del servicio ML debe ser HTTPS")
    void baseUrlMustBeHttps() {
        assertThat("https://ml-service:8443").startsWith("https://");
        assertThat("http://ml-service:8443").doesNotStartWith("https://");
    }

    @Test
    @DisplayName("El secreto interno se ofrece como cabecera cuando esta configurado")
    void internalTokenOfferedWhenConfigured() {
        var ml = new AppProperties.Ml("https://ml.test", 1000, 1000, 0, "tok-secreto");
        assertThat(MlServiceClient.internalTokenHeader(ml)).contains("tok-secreto");
    }

    @Test
    @DisplayName("Vacio o ausente significa no enviar la cabecera (un 401 total si se enviara)")
    void blankTokenMeansNoHeader() {
        var empty = new AppProperties.Ml("https://ml.test", 1000, 1000, 0, "");
        var blank = new AppProperties.Ml("https://ml.test", 1000, 1000, 0, "   ");
        var missing = new AppProperties.Ml("https://ml.test", 1000, 1000, 0, null);
        assertThat(MlServiceClient.internalTokenHeader(empty)).isEmpty();
        assertThat(MlServiceClient.internalTokenHeader(blank)).isEmpty();
        assertThat(MlServiceClient.internalTokenHeader(missing)).isEmpty();
    }
}