package com.aylzz.xmrforecast.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.aylzz.xmrforecast.market.MarketService;
import com.aylzz.xmrforecast.metrics.MetricsService;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.publicapi.PublicDashboardService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Todo lo que pasa por {@code @Cacheable} tiene que sobrevivir a la
 * serializacion de Redis.
 *
 * <p>{@code spring.cache.type: redis} hace que Spring guarde los resultados de
 * cache con {@code JdkSerializationRedisSerializer}, que exige payloads
 * {@link java.io.Serializable}. Si un tipo no lo es, el fallo NO ocurre al
 * leer: ocurre al ESCRIBIR, es decir dentro del propio metodo cacheado, y sale
 * como un 500 en la ruta entera.
 *
 * <p>Es un defecto que solo aparece cuando la cache ACIERTA-escritura, cosa que
 * no ocurre sin datos de mercado: en T-043 las pruebas de unidad pasaban y el
 * panel publico devolvia 500 en tres de sus seis rutas contra la pila real
 * ({@code DefaultSerializer requires a Serializable payload}). Este test ejecuta
 * la misma operacion que Redis, sin Redis, para que no pueda volver.
 */
class CachedPayloadSerializationTest {

    private static final Instant T = Instant.parse("2026-10-01T06:15:00Z");

    private static void assertRedisRoundTrip(String label, Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
                out.flush();
            }
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertNotNull(in.readObject(), label + ": la lectura de Redis devolvio null");
            }
        } catch (IOException | ClassNotFoundException ex) {
            fail(label + " no sobrevive a la serializacion JDK de Redis y produciria un 500: " + ex);
        }
    }

    private static MetricsService.MetricSet metricSet() {
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("truePositive", 4L);
        matrix.put("falsePositive", 2L);
        return new MetricsService.MetricSet(
                new BigDecimal("5.10"),
                new BigDecimal("7.20"),
                new BigDecimal("3.40"),
                new BigDecimal("0.61"),
                new BigDecimal("1.20"),
                matrix,
                120);
    }

    @Test
    @DisplayName("PublicSummary (cache public:summary) es serializable")
    void publicSummaryIsSerializable() {
        assertRedisRoundTrip(
                "PublicSummary",
                new PublicDashboardService.PublicSummary(
                        "XMR-USD",
                        new BigDecimal("160.50"),
                        new BigDecimal("158.00"),
                        new BigDecimal("2.50"),
                        new BigDecimal("1.58"),
                        new BigDecimal("162.00"),
                        new BigDecimal("157.10"),
                        new BigDecimal("1234.50"),
                        T,
                        T,
                        "yahoo-finance"));
    }

    @Test
    @DisplayName("PublicModelStatus (cache public:models) es serializable")
    void publicModelStatusIsSerializable() {
        assertRedisRoundTrip(
                "PublicModelStatus",
                new PublicDashboardService.PublicModelStatus(
                        "gru_base", MlModel.Family.GRU, MlModel.TaskType.REGRESSION, true));
    }

    @Test
    @DisplayName("PublicComparisonRow (cache public:comparison) es serializable")
    void publicComparisonRowIsSerializable() {
        assertRedisRoundTrip(
                "PublicComparisonRow",
                new PublicDashboardService.PublicComparisonRow(
                        "gru_base", "GRU", true, metricSet()));
    }

    @Test
    @DisplayName("PublicMetrics (cache public:metrics) es serializable")
    void publicMetricsIsSerializable() {
        assertRedisRoundTrip(
                "PublicMetrics",
                new PublicDashboardService.PublicMetrics(
                        "E-07-gru", true, metricSet(), metricSet(), metricSet()));
    }

    @Test
    @DisplayName("PublicMetrics sin corridas tambien es serializable (available=false)")
    void publicMetricsWithoutRunsIsSerializable() {
        assertRedisRoundTrip(
                "PublicMetrics(vacio)",
                new PublicDashboardService.PublicMetrics(null, false, null, null, null));
    }

    @Test
    @DisplayName("PublicStatus (cache public:status) es serializable")
    void publicStatusIsSerializable() {
        assertRedisRoundTrip(
                "PublicStatus",
                new PublicDashboardService.PublicStatus(
                        T, T, 1500L, 5L, 3L, "E-07-gru", "SUCCEEDED", T, "2026-10-01"));
    }

    @Test
    @DisplayName("MarketService.QuoteResponse (cache market:latest) es serializable")
    void quoteResponseIsSerializable() {
        assertRedisRoundTrip(
                "QuoteResponse",
                new MarketService.QuoteResponse(
                        "XMR-USD",
                        new BigDecimal("160.50"),
                        new BigDecimal("158.00"),
                        new BigDecimal("162.00"),
                        new BigDecimal("157.10"),
                        new BigDecimal("158.00"),
                        new BigDecimal("2.50"),
                        new BigDecimal("1.58"),
                        new BigDecimal("1234.50"),
                        T,
                        T,
                        "yahoo-finance"));
    }

    @Test
    @DisplayName("MarketService.CandleResponse (cache public:series) es serializable")
    void candleResponseIsSerializable() {
        assertRedisRoundTrip(
                "CandleResponse",
                new MarketService.CandleResponse(
                        "1",
                        "2026-10-01",
                        new BigDecimal("158.00"),
                        new BigDecimal("162.00"),
                        new BigDecimal("157.10"),
                        new BigDecimal("160.50"),
                        new BigDecimal("1234.50"),
                        new BigDecimal("2.50"),
                        new BigDecimal("1.58")));
    }

    @Test
    @DisplayName("MetricSet viaja serializado dentro de los payloads publicos")
    void metricSetIsSerializable() {
        assertRedisRoundTrip("MetricSet", metricSet());
    }

    @Test
    @DisplayName("La lista cacheada de la serie publica es serializable")
    void candleListIsSerializable() {
        assertRedisRoundTrip(
                "List<CandleResponse>",
                List.of(
                        new MarketService.CandleResponse(
                                "1",
                                "2026-10-01",
                                new BigDecimal("158.00"),
                                new BigDecimal("162.00"),
                                new BigDecimal("157.10"),
                                new BigDecimal("160.50"),
                                null,
                                null,
                                null)));
    }

    @Test
    @DisplayName("Las cadenas con acentos sobreviven a la ida y vuelta")
    void utf8StringsSurviveTheRoundTrip() {
        assertRedisRoundTrip("cadena con acentos", "Sesion con acentos · XMR-USD · 160,50");
    }
}
