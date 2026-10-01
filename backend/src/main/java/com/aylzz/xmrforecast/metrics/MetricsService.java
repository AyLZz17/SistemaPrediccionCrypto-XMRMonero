package com.aylzz.xmrforecast.metrics;

import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.experiment.Experiment;
import com.aylzz.xmrforecast.experiment.ExperimentRepository;
import com.aylzz.xmrforecast.experiment.ExperimentRun;
import com.aylzz.xmrforecast.experiment.ExperimentRunRepository;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.mlmodel.MlModelRepository;
import com.aylzz.xmrforecast.mlmodel.ModelVersion;
import com.aylzz.xmrforecast.mlmodel.ModelVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consulta de metricas de evaluacion.
 *
 * <p>Las metricas se calculan <strong>en el servicio ML</strong> y se guardan
 * aqui; este servicio no recalcula nada. Esa separacion es deliberada: R-02, R-04
 * y R-24 son reglas del calculo, y duplicar el calculo en el backend abriria la
 * puerta a que las cifras publicadas y las evaluadas diverjan.
 *
 * <p>La comparacion se construye siempre sobre la <strong>misma</strong> corrida
 * y, dentro de ella, sobre la misma particion (R-05). Cuando un experimento tiene
 * varias corridas, se toma la mas reciente que tenga metricas de la particion
 * pedida, y se dice cual es: una comparacion entre corridas distintas no seria
 * comparable.
 */
@Service
public class MetricsService {

    private static final int MAX_PAGE_SIZE = 200;

    private final MetricRepository metricRepository;
    private final ExperimentRepository experimentRepository;
    private final ExperimentRunRepository runRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final MlModelRepository modelRepository;

    public MetricsService(MetricRepository metricRepository,
                          ExperimentRepository experimentRepository,
                          ExperimentRunRepository runRepository,
                          ModelVersionRepository modelVersionRepository,
                          MlModelRepository modelRepository) {
        this.metricRepository = metricRepository;
        this.experimentRepository = experimentRepository;
        this.runRepository = runRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.modelRepository = modelRepository;
    }

    /**
     * Comparacion de modelos de un experimento.
     *
     * <p>Devuelve una fila por modelo con sus metricas de la particion pedida
     * (validacion por defecto, porque es la que decide el campeon) y marca cual es
     * el campeon vigente.
     */
    @Transactional(readOnly = true)
    public List<ComparisonRow> compare(Long experimentId, Metric.Split split) {
        Experiment experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> com.aylzz.xmrforecast.common.ApiException.notFound(
                        "EXPERIMENT_NOT_FOUND", "El experimento no existe."));

        ExperimentRun run = latestRunWithMetrics(experiment.getId(), split);
        List<Metric> metrics = run == null
                ? List.of() : metricRepository.findAllByRunIdAndSplit(run.getId(), split);

        Map<Long, ModelVersion> versions = new LinkedHashMap<>();
        // Los modelos tambien se resuelven en bloque. Antes se hacia un findById
        // por fila de metricas, de modo que una comparacion de 20 modelos eran
        // 21 consultas en lugar de 2.
        Map<Long, MlModel> models = loadModels(metrics, versions);
        List<ComparisonRow> rows = new ArrayList<>();
        for (Metric metric : metrics) {
            ModelVersion version = resolveVersion(metric, versions);
            Long modelId = version == null ? null : version.getModelId();
            MlModel model = modelId == null ? null : models.get(modelId);
            rows.add(new ComparisonRow(
                    model == null ? "modelo " + Ids.of(metric.getModelVersionId())
                            : model.getModelKey(),
                    Ids.of(modelId),
                    model == null ? null : model.getModelKey(),
                    model == null ? null : model.getFamily().name(),
                    version != null && version.isChampion(),
                    MetricSet.of(metric),
                    Ids.of(run == null ? null : run.getId()),
                    split.name()));
        }
        // Orden estable: el campeon primero, despues por MAE ascendente. Con datos
        // iguales, el orden no debe depender del plan de ejecucion de la base de datos.
        rows.sort(Comparator.comparing(ComparisonRow::isChampion).reversed()
                .thenComparing(row -> row.metrics().mae() == null
                        ? BigDecimal.valueOf(Long.MAX_VALUE) : row.metrics().mae()));
        return rows;
    }

    /**
     * Metricas agregadas de un experimento, separadas por particion.
     *
     * <p>Devuelve validacion y prueba por separado, nunca mezcladas: R-04 obliga
     * a que el conjunto de prueba se use una sola vez y R-24 a que no decida nada.
     */
    @Transactional(readOnly = true)
    public ExperimentMetrics experimentMetrics(Long experimentId) {
        experimentRepository.findById(experimentId)
                .orElseThrow(() -> com.aylzz.xmrforecast.common.ApiException.notFound(
                        "EXPERIMENT_NOT_FOUND", "El experimento no existe."));

        MetricSet validation = aggregate(experimentId, Metric.Split.VALIDATION);
        MetricSet test = aggregate(experimentId, Metric.Split.TEST);
        return new ExperimentMetrics(
                Ids.of(experimentId),
                validation.mae() == null && test.mae() == null ? null : validation,
                validation,
                test);
    }

    /** Mediana del MAE entre modelos: mas robusta que la media frente a un outlier. */
    private MetricSet aggregate(Long experimentId, Metric.Split split) {
        Long runId = latestRunId(experimentId, split);
        if (runId == null) {
            return MetricSet.empty();
        }
        List<Metric> metrics = metricRepository.findAllByRunIdAndSplit(runId, split);
        if (metrics.isEmpty()) {
            return MetricSet.empty();
        }
        List<BigDecimal> maes = metrics.stream()
                .map(Metric::getMae)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        // Mediana de verdad: con un numero par de valores, el elemento central superior no
// es la mediana sino el maximo de la mitad baja. Para 2 modelos, `get(1)`
// devolvia directamente el peor MAE.
        BigDecimal median = median(maes);

        // Media del acierto de direccion entre los modelos que la publicaron.
        List<BigDecimal> directions = metrics.stream()
                .map(Metric::getDirectionAccuracy)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal meanDirection = directions.isEmpty() ? null
                : directions.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(directions.size()), 6, RoundingMode.HALF_UP);

        return new MetricSet(median, null, null, meanDirection, null, null, metrics.size());
    }

    /** Mediana de una lista ya ordenada; con cardinalidad par promedia los dos centrales. */
    static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted == null || sorted.isEmpty()) {
            return null;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private Long latestRunId(Long experimentId, Metric.Split split) {
        ExperimentRun run = latestRunWithMetrics(experimentId, split);
        return run == null ? null : run.getId();
    }

    /**
     * Corrida mas reciente del experimento que tenga metricas de la particion.
     * Comparar modelos de corridas distintas no seria una comparacion (R-05).
     *
     * <p>Una sola consulta para todas las corridas del experimento: la version
     * anterior preguntaba metricas de una en una (N+1), y como
     * {@link #experimentMetrics} lo hace para dos particiones, eran 2N consultas
     * para responder una peticion.
     */
    private ExperimentRun latestRunWithMetrics(Long experimentId, Metric.Split split) {
        List<ExperimentRun> runs = runRepository.findAllByExperimentId(experimentId);
        if (runs.isEmpty()) {
            return null;
        }
        List<Long> runIds = runs.stream().map(ExperimentRun::getId).toList();
        java.util.Set<Long> withMetrics = metricRepository.findAllByRunIdInAndSplit(runIds, split)
                .stream()
                .map(Metric::getRunId)
                .collect(java.util.stream.Collectors.toSet());
        return runs.stream()
                .filter(run -> withMetrics.contains(run.getId()))
                .max(Comparator.comparing(ExperimentRun::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Carga en una sola consulta los modelos referenciados por las metricas dadas. */
    private Map<Long, MlModel> loadModels(List<Metric> metrics, Map<Long, ModelVersion> versions) {
        java.util.Set<Long> modelIds = new java.util.LinkedHashSet<>();
        for (Metric metric : metrics) {
            ModelVersion version = resolveVersion(metric, versions);
            if (version != null && version.getModelId() != null) {
                modelIds.add(version.getModelId());
            }
        }
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MlModel> models = new LinkedHashMap<>();
        modelRepository.findAllById(modelIds).forEach(model -> models.put(model.getId(), model));
        return models;
    }

    private ModelVersion resolveVersion(Metric metric, Map<Long, ModelVersion> cache) {
        Long versionId = metric.getModelVersionId();
        if (versionId == null) {
            // Metricas de un baseline sin artefacto persistido: no hay version que
            // resolver, pero la fila sigue siendo valida y debe aparecer.
            return null;
        }
        return cache.computeIfAbsent(versionId,
                id -> modelVersionRepository.findById(id).orElse(null));
    }

    /**
     * Metricas de una fila. Los nombres coinciden con los que consume la
     * interfaz; {@code meanStdDev} es la desviacion media entre semillas, que se
     * exige reportar junto a la media (R-08).
     */
    public record MetricSet(
            BigDecimal mae,
            BigDecimal rmse,
            BigDecimal mape,
            BigDecimal directionAccuracy,
            BigDecimal meanStdDev,
            Map<String, Object> confusionMatrix,
            Integer samples
    )
            // MetricSet viaja DENTRO de los payloads cacheados del panel publico
            // (PublicMetrics, PublicComparisonRow): si no es Serializable, Redis lo
            // rechaza al guardar y la ruta entera devuelve 500.
            implements Serializable {

        private static final long serialVersionUID = 1L;

        static MetricSet of(Metric metric) {
            BigDecimal stddev = null;
            if (metric.getStddev() != null) {
                Object mae = metric.getStddev().get("mae");
                if (mae != null) {
                    try {
                        stddev = new BigDecimal(String.valueOf(mae));
                    } catch (NumberFormatException ignored) {
                        // Una desviacion ilegible se omite en lugar de romper la
                        // comparacion entera: el resto de metricas sigue siendo valido.
                    }
                }
            }
            return new MetricSet(metric.getMae(), metric.getRmse(), metric.getMape(),
                    metric.getDirectionAccuracy(), stddev,
                    metric.getConfusionMatrix(), metric.getSamples());
        }

        static MetricSet empty() {
            return new MetricSet(null, null, null, null, null, null, 0);
        }
    }

    /** Fila de la comparacion. {@code label} es la clave estable del modelo. */
    public record ComparisonRow(
            String label,
            String modelId,
            String modelName,
            String family,
            boolean isChampion,
            MetricSet metrics,
            String runId,
            String split
    ) {
    }

    /** Metricas de un experimento, por particion. */
    public record ExperimentMetrics(
            String experimentId,
            MetricSet best,
            MetricSet validation,
            MetricSet test
    ) {
    }
}
