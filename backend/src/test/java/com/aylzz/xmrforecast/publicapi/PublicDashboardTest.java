package com.aylzz.xmrforecast.publicapi;

import com.aylzz.xmrforecast.common.LegalDocuments;
import com.aylzz.xmrforecast.experiment.Experiment;
import com.aylzz.xmrforecast.experiment.ExperimentRepository;
import com.aylzz.xmrforecast.market.MarketData;
import com.aylzz.xmrforecast.market.MarketDataRepository;
import com.aylzz.xmrforecast.market.MarketService;
import com.aylzz.xmrforecast.metrics.Metric;
import com.aylzz.xmrforecast.metrics.MetricsService;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.mlmodel.MlModelRepository;
import com.aylzz.xmrforecast.mlmodel.ModelVersion;
import com.aylzz.xmrforecast.mlmodel.ModelVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Superficie publica: agregados globales, sin identificadores internos.
 *
 * <p>Estas pruebas fijan dos cosas que no deben cambiar sin que nadie lo note:
 * (1) la superficie anonima solo pide datos globales —nunca un identificador
 * sobre el que pudiera hacerse IDOR— y (2) cuando no hay resultados publicables
 * se declara la ausencia en lugar de rellenar la respuesta con cifras inventadas
 * (R-21).
 */
class PublicDashboardTest {

    private final MarketService marketService = mock(MarketService.class);
    private final MetricsService metricsService = mock(MetricsService.class);
    private final MlModelRepository modelRepository = mock(MlModelRepository.class);
    private final ModelVersionRepository modelVersionRepository = mock(ModelVersionRepository.class);
    private final ExperimentRepository experimentRepository = mock(ExperimentRepository.class);
    private final MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);

    private final PublicDashboardService service = new PublicDashboardService(marketService,
            metricsService, modelRepository, modelVersionRepository, experimentRepository,
            marketDataRepository);

    private static MarketService.QuoteResponse quote() {
        return new MarketService.QuoteResponse("XMR-USD", new BigDecimal("160.50"),
                new BigDecimal("158.00"), new BigDecimal("162.00"), new BigDecimal("157.10"),
                new BigDecimal("158.00"), new BigDecimal("2.50"), new BigDecimal("1.5823"),
                new BigDecimal("1234.5"), Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T06:15:00Z"), "yahoo-finance");
    }

    private static Experiment experimento(long id, String code) {
        Experiment experiment = new Experiment();
        experiment.setId(id);
        experiment.setCode(code);
        experiment.setStatus(Experiment.ExperimentStatus.COMPLETED);
        experiment.setUpdatedAt(Instant.parse("2026-09-30T12:00:00Z"));
        return experiment;
    }

    // ---------------------------------------------------------- resumen

    @Test
    @DisplayName("el resumen normaliza el simbolo y no pide nada mas que mercado global")
    void resumenNormalizaElSimbolo() {
        when(marketService.latest("XMR-USD")).thenReturn(quote());

        PublicDashboardService.PublicSummary summary = service.summary("  xmr-usd ");

        assertThat(summary.symbol()).isEqualTo("XMR-USD");
        assertThat(summary.price()).isEqualByComparingTo("160.50");
        assertThat(summary.changePercent()).isEqualByComparingTo("1.5823");
        assertThat(summary.updatedAt()).isEqualTo("2026-10-01T06:15:00Z");
        verify(marketService).latest("XMR-USD");
    }

    @Test
    @DisplayName("la serie anonima nunca supera el tope publico aunque se pida mas")
    void laSerieSeAcoruntaAlTopePublico() {
        when(marketService.series(eq("XMR-USD"), any(), any(), anyInt())).thenReturn(List.of());

        service.series("XMR-USD", 100_000);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(marketService).series(eq("XMR-USD"), any(), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(PublicDashboardService.MAX_PUBLIC_SERIES);
    }

    @Test
    @DisplayName("un limite no positivo se corrige a una vela en vez de fallar en el servicio")
    void limiteNoPositivoSeCorrige() {
        when(marketService.series(eq("XMR-USD"), any(), any(), anyInt())).thenReturn(List.of());

        service.series("XMR-USD", 0);

        verify(marketService).series(eq("XMR-USD"), any(), any(), eq(1));
    }

    // --------------------------------------------------------- metricas

    @Test
    @DisplayName("sin experimento terminado las metricas declaran que aun no hay resultados")
    void metricasSinExperimentoDeclaranAusencia() {
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED)).thenReturn(Optional.empty());

        PublicDashboardService.PublicMetrics metrics = service.metrics();

        assertThat(metrics.available()).isFalse();
        assertThat(metrics.experimentCode()).isNull();
        assertThat(metrics.validation()).isNull();
        // No se inventan cifras: el servicio de metricas ni siquiera se consulta.
        verify(metricsService, never()).experimentMetrics(any());
        verify(metricsService, never()).compare(any(), any());
    }

    @Test
    @DisplayName("con un experimento terminado se publican sus metricas agregadas")
    void metricasPublicanElUltimoExperimento() {
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED))
                .thenReturn(Optional.of(experimento(9L, "E-07-gru")));
        MetricsService.MetricSet validation = new MetricsService.MetricSet(
                new BigDecimal("5.1"), new BigDecimal("7.2"), new BigDecimal("3.4"),
                new BigDecimal("0.61"), null, Map.of(), 120);
        MetricsService.MetricSet test = new MetricsService.MetricSet(
                new BigDecimal("6.0"), new BigDecimal("8.1"), new BigDecimal("3.9"),
                new BigDecimal("0.58"), null, Map.of(), 40);
        when(metricsService.experimentMetrics(9L))
                .thenReturn(new MetricsService.ExperimentMetrics("9", validation, validation, test));

        PublicDashboardService.PublicMetrics metrics = service.metrics();

        assertThat(metrics.available()).isTrue();
        assertThat(metrics.experimentCode()).isEqualTo("E-07-gru");
        assertThat(metrics.validation().mae()).isEqualByComparingTo("5.1");
        assertThat(metrics.test().directionAccuracy()).isEqualByComparingTo("0.58");
    }

    @Test
    @DisplayName("la comparacion publica se calcula sobre VALIDATION y se publica sin runId")
    void comparacionUsaValidacionYSinIdsInternos() {
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED))
                .thenReturn(Optional.of(experimento(9L, "E-07-gru")));
        MetricsService.MetricSet metrics = new MetricsService.MetricSet(
                new BigDecimal("5.1"), null, null, null, null, Map.of(), 120);
        when(metricsService.compare(9L, Metric.Split.VALIDATION)).thenReturn(List.of(
                new MetricsService.ComparisonRow("gru_base", "mv-1", "GRU base", "GRU",
                        true, metrics, "run-interno", "VALIDATION")));

        List<PublicDashboardService.PublicComparisonRow> rows = service.comparison();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).label()).isEqualTo("gru_base");
        assertThat(rows.get(0).family()).isEqualTo("GRU");
        assertThat(rows.get(0).isChampion()).isTrue();
        assertThat(rows.get(0).metrics().mae()).isEqualByComparingTo("5.1");
        verify(metricsService).compare(9L, Metric.Split.VALIDATION);
    }

    @Test
    @DisplayName("sin experimento la comparacion viene vacia, no un error")
    void comparacionSinExperimentoEstaVacia() {
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED)).thenReturn(Optional.empty());

        assertThat(service.comparison()).isEmpty();
        verify(metricsService, never()).compare(any(), any());
    }

    // --------------------------------------------------------- modelos

    @Test
    @DisplayName("el estado de modelos marca quien tiene campeon y no expone versiones")
    void estadoDeModelos() {
        MlModel lstm = new MlModel();
        lstm.setId(1L);
        lstm.setModelKey("lstm_base");
        lstm.setFamily(MlModel.Family.LSTM);
        lstm.setTaskType(MlModel.TaskType.REGRESSION);
        MlModel arima = new MlModel();
        arima.setId(2L);
        arima.setModelKey("arima_base");
        arima.setFamily(MlModel.Family.ARIMA);
        arima.setTaskType(MlModel.TaskType.REGRESSION);
        when(modelRepository.findAllByOrderByModelKeyAsc(any(PageRequest.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(lstm, arima)));
        ModelVersion campeon = new ModelVersion();
        campeon.setModelId(1L);
        campeon.setChampion(true);
        when(modelVersionRepository.findAllByModelIdInAndChampionTrue(
                org.mockito.ArgumentMatchers.argThat(ids -> ids != null && ids.contains(1L))))
                .thenReturn(List.of(campeon));

        List<PublicDashboardService.PublicModelStatus> models = service.models();

        assertThat(models).hasSize(2);
        assertThat(models.get(0).hasChampion()).isTrue();
        assertThat(models.get(1).hasChampion()).isFalse();
        assertThat(models.get(0).name()).isEqualTo("lstm_base");
    }

    // ---------------------------------------------------------- estado

    @Test
    @DisplayName("el estado junta frescura de datos, catalogo y experimento en vocabulario publico")
    void estadoDelSistema() {
        MarketData ultimo = new MarketData();
        ultimo.setIngestedAt(Instant.parse("2026-10-01T06:15:00Z"));
        when(marketDataRepository.findTopByOrderByIngestedAtDesc()).thenReturn(Optional.of(ultimo));
        when(marketDataRepository.countBySymbol("XMR-USD")).thenReturn(1500L);
        when(modelRepository.count()).thenReturn(5L);
        when(modelVersionRepository.countByChampionTrue()).thenReturn(3L);
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED))
                .thenReturn(Optional.of(experimento(9L, "E-07-gru")));

        PublicDashboardService.PublicStatus status = service.status();

        assertThat(status.dataUpdatedAt()).isEqualTo("2026-10-01T06:15:00Z");
        assertThat(status.dataPoints()).isEqualTo(1500L);
        assertThat(status.models()).isEqualTo(5L);
        assertThat(status.champions()).isEqualTo(3L);
        assertThat(status.experimentCode()).isEqualTo("E-07-gru");
        assertThat(status.experimentStatus())
                .as("el cliente no conoce el vocabulario persistido COMPLETED (R-43)")
                .isEqualTo("SUCCEEDED");
        assertThat(status.legalVersion()).isEqualTo(LegalDocuments.CURRENT_VERSION);
    }

    @Test
    @DisplayName("sin datos de mercado el estado lo dice con nulos, no con una fecha inventada")
    void estadoSinDatos() {
        when(marketDataRepository.findTopByOrderByIngestedAtDesc()).thenReturn(Optional.empty());
        when(marketDataRepository.countBySymbol("XMR-USD")).thenReturn(0L);
        when(modelRepository.count()).thenReturn(0L);
        when(modelVersionRepository.countByChampionTrue()).thenReturn(0L);
        when(experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED)).thenReturn(Optional.empty());

        PublicDashboardService.PublicStatus status = service.status();

        assertThat(status.dataUpdatedAt()).isNull();
        assertThat(status.experimentCode()).isNull();
        assertThat(status.experimentStatus()).isNull();
    }
}
