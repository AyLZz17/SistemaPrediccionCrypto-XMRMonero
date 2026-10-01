package com.aylzz.xmrforecast.publicapi;

import com.aylzz.xmrforecast.common.LegalDocuments;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.experiment.Experiment;
import com.aylzz.xmrforecast.experiment.ExperimentRepository;
import com.aylzz.xmrforecast.market.MarketData;
import com.aylzz.xmrforecast.market.MarketDataRepository;
import com.aylzz.xmrforecast.market.MarketService;
import com.aylzz.xmrforecast.metrics.Metric;
import com.aylzz.xmrforecast.metrics.MetricsService;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.mlmodel.MlModelRepository;
import com.aylzz.xmrforecast.mlmodel.ModelVersionRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Datos del dashboard publico: la primera pantalla de la aplicacion.
 *
 * <p><strong>Superficie anonima, deliberadamente estrecha.</strong> Solo lectura,
 * solo agregados globales y nunca un identificador de usuario, de prediccion, de
 * experimento interno o de configuracion (R-26). Los identificadores internos
 * que si viajan —el id opaco de una vela— no son sensibles y el cliente los
 * necesita para graficar.
 *
 * <p>Todas las respuestas se sirven de cache ({@code spring.cache}, Redis con
 * TTL de 300 s): la superficie anonima es la que mas trafico recibe y no puede
 * traducirse en un barrido de tablas por cada visita.
 */
@Service
public class PublicDashboardService {

    /** Tope de velas que la superficie anonima esta dispuesta a servir. */
    public static final int MAX_PUBLIC_SERIES = 365;

    /** Catalogo de modelos que se expone completo. */
    private static final int MAX_PUBLIC_MODELS = 200;

    private final MarketService marketService;
    private final MetricsService metricsService;
    private final MlModelRepository modelRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final ExperimentRepository experimentRepository;
    private final MarketDataRepository marketDataRepository;

    public PublicDashboardService(MarketService marketService,
                                  MetricsService metricsService,
                                  MlModelRepository modelRepository,
                                  ModelVersionRepository modelVersionRepository,
                                  ExperimentRepository experimentRepository,
                                  MarketDataRepository marketDataRepository) {
        this.marketService = marketService;
        this.metricsService = metricsService;
        this.modelRepository = modelRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.experimentRepository = experimentRepository;
        this.marketDataRepository = marketDataRepository;
    }

    /**
     * Resumen del mercado: la tarjeta principal de la pantalla.
     *
     * <p>Todos los records de esta clase son {@link Serializable}: Redis guarda la
     * cache con serializacion JDK y un payload que no lo es hace fallar el PUT con
     * {@code DefaultSerializer requires a Serializable payload}, lo que se traduce en
     * un 500 en la ruta entera. Sin datos no se nota (la cache no llega a escribirse),
     * pero con datos la primera pantalla se cae: comprobado en T-043 con la pila real
     * y fijado por {@code CachedPayloadSerializationTest}.
     */
    public record PublicSummary(
            String symbol,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal change,
            BigDecimal changePercent,
            BigDecimal high,
            BigDecimal low,
            BigDecimal volume,
            Instant marketTime,
            Instant updatedAt,
            String source
    ) implements Serializable {

        private static final long serialVersionUID = 1L;
    }

    /** Estado publico de un modelo: existe y tiene campeon o no. */
    public record PublicModelStatus(
            String name,
            MlModel.Family family,
            MlModel.TaskType task,
            boolean hasChampion
    ) implements Serializable {

        private static final long serialVersionUID = 1L;
    }

    /**
     * Fila de comparacion publica. Sin {@code runId} ni identificadores de
     * experimento: lo que se publica es la capacidad evaluada de cada familia,
     * no la maquinaria interna.
     */
    public record PublicComparisonRow(
            String label,
            String family,
            boolean isChampion,
            MetricsService.MetricSet metrics
    ) implements Serializable {

        private static final long serialVersionUID = 1L;
    }

    /**
     * Metricas agregadas del ultimo experimento completado. {@code available} es
     * falso cuando aun no hay corridas: decir "todavia no hay resultados" es
     * preferible a inventarlos (R-21, R-09).
     */
    public record PublicMetrics(
            String experimentCode,
            boolean available,
            MetricsService.MetricSet best,
            MetricsService.MetricSet validation,
            MetricsService.MetricSet test
    ) implements Serializable {

        private static final long serialVersionUID = 1L;
    }

    /** Frescura de los datos y estado general del catalogo. */
    public record PublicStatus(
            Instant generatedAt,
            Instant dataUpdatedAt,
            long dataPoints,
            long models,
            long champions,
            String experimentCode,
            String experimentStatus,
            Instant experimentUpdatedAt,
            String legalVersion
    ) implements Serializable {

        private static final long serialVersionUID = 1L;
    }

    @Cacheable(value = "public:summary",
            key = "T(com.aylzz.xmrforecast.common.QueryParams).symbol(#symbol)")
    @Transactional(readOnly = true)
    public PublicSummary summary(String symbol) {
        MarketService.QuoteResponse quote = marketService.latest(QueryParams.symbol(symbol));
        return new PublicSummary(quote.symbol(), quote.price(), quote.previousClose(),
                quote.change(), quote.changePercent(), quote.high(), quote.low(), quote.volume(),
                quote.marketTime(), quote.updatedAt(), quote.source());
    }

    @Cacheable(value = "public:series",
            key = "T(com.aylzz.xmrforecast.common.QueryParams).symbol(#symbol) + ':' + #limit")
    @Transactional(readOnly = true)
    public List<MarketService.CandleResponse> series(String symbol, int limit) {
        // El tope se aplica aqui y no solo en la anotacion del controlador: un
        // llamante interno no debe poder pedir mas de lo que la superficie
        // anonima ha decidido servir.
        int capped = Math.min(Math.max(limit, 1), MAX_PUBLIC_SERIES);
        return marketService.series(QueryParams.symbol(symbol), null, null, capped);
    }

    @Cacheable("public:models")
    @Transactional(readOnly = true)
    public List<PublicModelStatus> models() {
        List<MlModel> catalog = modelRepository
                .findAllByOrderByModelKeyAsc(PageRequest.of(0, MAX_PUBLIC_MODELS))
                .getContent();
        if (catalog.isEmpty()) {
            return List.of();
        }
        Set<Long> withChampion = modelVersionRepository
                .findAllByModelIdInAndChampionTrue(
                        catalog.stream().map(MlModel::getId).collect(Collectors.toSet()))
                .stream().map(v -> v.getModelId()).collect(Collectors.toSet());
        return catalog.stream()
                .map(model -> new PublicModelStatus(model.getModelKey(), model.getFamily(),
                        model.getTaskType(), withChampion.contains(model.getId())))
                .toList();
    }

    @Cacheable("public:metrics")
    @Transactional(readOnly = true)
    public PublicMetrics metrics() {
        Optional<Experiment> latest = latestCompletedExperiment();
        if (latest.isEmpty()) {
            return new PublicMetrics(null, false, null, null, null);
        }
        Experiment experiment = latest.get();
        MetricsService.ExperimentMetrics value = metricsService.experimentMetrics(experiment.getId());
        return new PublicMetrics(experiment.getCode(), true, value.best(), value.validation(),
                value.test());
    }

    /**
     * Comparacion general de modelos sobre la MISMA corrida y la MISMA particion.
     *
     * <p>Se publica {@code VALIDATION} por defecto de la API (R-24): es la
     * particion con la que se elige campeon, y ofrecer {@code TEST} como cara
     * publica invitaria a leerlo como resultado final.
     */
    @Cacheable("public:comparison")
    @Transactional(readOnly = true)
    public List<PublicComparisonRow> comparison() {
        Optional<Experiment> latest = latestCompletedExperiment();
        if (latest.isEmpty()) {
            return List.of();
        }
        return metricsService.compare(latest.get().getId(), Metric.Split.VALIDATION).stream()
                .map(row -> new PublicComparisonRow(row.label(), row.family(), row.isChampion(),
                        row.metrics()))
                .toList();
    }

    @Cacheable("public:status")
    @Transactional(readOnly = true)
    public PublicStatus status() {
        Instant dataUpdatedAt = marketDataRepository.findTopByOrderByIngestedAtDesc()
                .map(MarketData::getIngestedAt)
                .orElse(null);
        long points = marketDataRepository.countBySymbol(QueryParams.symbol(null));
        long models = modelRepository.count();
        long champions = modelVersionRepository.countByChampionTrue();

        Optional<Experiment> latest = latestCompletedExperiment();
        String experimentCode = null;
        String experimentStatus = null;
        Instant experimentUpdatedAt = null;
        if (latest.isPresent()) {
            Experiment experiment = latest.get();
            experimentCode = experiment.getCode();
            // Vocabulario publico, no el persistido (R-43): COMPLETED no se
            // publica como "COMPLETED" sino como SUCCEEDED, que es el unico que
            // conoce el cliente.
            experimentStatus = "SUCCEEDED";
            experimentUpdatedAt = experiment.getUpdatedAt();
        }

        return new PublicStatus(Instant.now(), dataUpdatedAt, points, models, champions,
                experimentCode, experimentStatus, experimentUpdatedAt,
                LegalDocuments.CURRENT_VERSION);
    }

    private Optional<Experiment> latestCompletedExperiment() {
        return experimentRepository.findTopByStatusOrderByCreatedAtDesc(
                Experiment.ExperimentStatus.COMPLETED);
    }
}
