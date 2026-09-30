package com.aylzz.xmrforecast.prediction;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.ml.MlServiceClient;
import com.aylzz.xmrforecast.mlmodel.MlModel;
import com.aylzz.xmrforecast.mlmodel.MlModelRepository;
import com.aylzz.xmrforecast.mlmodel.ModelVersion;
import com.aylzz.xmrforecast.mlmodel.ModelVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orquestacion de predicciones.
 *
 * <p>Idempotencia: la tabla {@code predictions} tiene una restriccion unica sobre
 * (model_version_id, symbol, target_date). Repetir la misma peticion devuelve la
 * prediccion existente en lugar de crear otra (seccion 10).
 *
 * <p>Aislamiento entre usuarios: <strong>toda</strong> lectura se filtra por
 * {@code requested_by}, incluido el listado filtrado por simbolo. No existe
 * ninguna firma de este servicio que permita consultar por simbolo sin acotar
 * tambien por propietario (OWASP API1).
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
    private static final int DEFAULT_LOOKBACK = 30;
    private static final int MAX_LOOKBACK = 365;

    /** Aviso obligatorio en cada respuesta (R-11, R-12). */
    static final String DISCLAIMER = "Pronostico de capacidad predictiva evaluada. "
            + "No es asesoria financiera, no promete rentabilidad y no simula operaciones.";

    private final PredictionRepository predictionRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final MlModelRepository modelRepository;
    private final MlServiceClient mlClient;

    public PredictionService(PredictionRepository predictionRepository,
                             ModelVersionRepository modelVersionRepository,
                             MlModelRepository modelRepository,
                             MlServiceClient mlClient) {
        this.predictionRepository = predictionRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.modelRepository = modelRepository;
        this.mlClient = mlClient;
    }

    /**
     * Solicita una prediccion al servicio ML y la persiste con su trazabilidad.
     *
     * <p>El cliente puede enviar {@code modelId} (un modelo logico, del que se
     * usa su version campeon) o {@code modelVersionId} (una version concreta).
     * Se prefiere la version explicita: es mas precisa y es la que exige R-28
     * cuando el campeon se ha movido entre la lectura de la UI y el envio.
     */
    @Transactional
    public PredictionResponse create(CreatePredictionRequest request, Long userId) {
        ModelVersion modelVersion = resolveModelVersion(request);

        if (!modelVersion.isIntegrityVerified()) {
            // R-28: no se sirve un artefacto que no haya pasado el gate de integridad.
            throw ApiException.unprocessable("MODEL_NOT_VERIFIED",
                    "La version de modelo no supero la verificacion de integridad.");
        }

        String symbol = QueryParams.symbol(request.symbol());
        LocalDate targetDate = request.targetDate() == null
                ? LocalDate.now().plusDays(1) : request.targetDate();

        // Idempotencia: si ya existe para este modelo/simbolo/fecha, se devuelve.
        var existing = predictionRepository
                .findByModelVersionIdAndSymbolAndTargetDate(modelVersion.getId(), symbol, targetDate);
        if (existing.isPresent()) {
            log.info("Prediccion ya existente, se devuelve sin recalcular (id={})", existing.get().getId());
            return toResponse(existing.get(), new HashMap<>());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        // Contrato con el servicio ML (app/api/schemas.py::PredictRequest), que
        // prohibe campos extra. La semilla viaja en la version del modelo, no aqui.
        payload.put("model_key", modelKeyOf(modelVersion));
        payload.put("version", modelVersion.getVersion());
        payload.put("symbol", symbol);
        payload.put("lookback_days", clampLookback(request.lookbackDays()));

        JsonNode response = mlClient.predict(payload);

        Prediction prediction = new Prediction();
        prediction.setModelVersionId(modelVersion.getId());
        prediction.setSymbol(symbol);
        // La fecha la decide el servicio ML a partir del ultimo dia de su snapshot.
        // Si devuelve algo que no es una fecha ISO, la peticion no puede completarse:
        // `LocalDate.parse` lanzaba DateTimeParseException, que el manejador global no
        // traduce, y el cliente recibia 500 en lugar de un 502 con un diagnostico util.
        prediction.setTargetDate(targetDateFrom(response, targetDate));
        prediction.setPredictedClose(decimal(response, "predicted_close"));
        prediction.setPredictedDirection(direction(response.path("predicted_direction").asText("FLAT")));
        prediction.setConfidence(decimal(response, "confidence"));
        prediction.setDatasetVersionId(modelVersion.getDatasetVersionId());
        prediction.setArtifactSha256(modelVersion.getArtifactSha256());
        prediction.setConfigSha256(modelVersion.getConfigSha256());
        prediction.setSeed(modelVersion.getSeed());
        prediction.setTrace(jsonToMap(response.path("trace")));
        prediction.setRequestedBy(userId);
        prediction.setRequestId(RequestContext.requestId());
        prediction.setStatus(prediction.getPredictedClose() == null
                ? Prediction.Status.FAILED : Prediction.Status.READY);
        predictionRepository.save(prediction);

        return toResponse(prediction, new HashMap<>());
    }

    /**
     * Listado acotado al propietario. El filtro por simbolo es un <em>and</em>
     * adicional, nunca un alternativa: ambos caminos parametrizan {@code userId}.
     */
    @Transactional(readOnly = true)
    public PageResponse<PredictionResponse> listForUser(Long userId, String symbol, int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, 100));
        Page<Prediction> result = (symbol == null || symbol.isBlank())
                ? predictionRepository.findAllByRequestedByOrderByCreatedAtDesc(userId, pageable)
                : predictionRepository.findAllByRequestedByAndSymbolOrderByTargetDateDesc(
                        userId, QueryParams.symbol(symbol), pageable);
        // El nombre del modelo se resuelve una vez por pagina y se reutiliza: sin
        // este memo, una pagina de 100 filas emitiria 200 consultas adicionales.
        Map<Long, String> names = new HashMap<>();
        return PageResponse.of(result, prediction -> toResponse(prediction, names));
    }

    /** Lectura acotada al propietario: 404 en lugar de 403 para no revelar existencia. */
    @Transactional(readOnly = true)
    public PredictionResponse getOne(Long id, Long userId, boolean admin) {
        Prediction prediction = predictionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("PREDICTION_NOT_FOUND",
                        "La prediccion no existe."));
        if (!admin && (prediction.getRequestedBy() == null
                || !prediction.getRequestedBy().equals(userId))) {
            log.warn("Intento de acceso a prediccion ajena id={} userId={}", id, userId);
            throw ApiException.notFound("PREDICTION_NOT_FOUND", "La prediccion no existe.");
        }
        return toResponse(prediction, new HashMap<>());
    }

    /**
     * Clave del modelo en el registro del servicio ML.
     *
     * <p>Antes de arreglarlo, si la version no estaba enlazada a un modelo (o el
     * modelo ya no existia) se enviaba el <em>id numerico</em> de la version como
     * {@code model_key}. El servicio ML resuelve por clave de registro
     * ({@code ModelService.resolve}), de modo que la peticion llegaba, el registro
     * no encontraba esa clave y respondia 404: el fallo se manifestaba como
     * "modelo no encontrado" en el servicio equivocado, con una causa real
     * —una referencia rota— que nadie podía ver. Ahora el dato incompleto se
     * detecta aqui, donde el diagnostico es correcto.
     */
    private String modelKeyOf(ModelVersion modelVersion) {
        Long modelId = modelVersion.getModelId();
        if (modelId == null) {
            throw ApiException.unprocessable("MODEL_NOT_LINKED",
                    "La version de modelo no esta enlazada a un modelo del catalogo.");
        }
        return modelRepository.findById(modelId)
                .map(MlModel::getModelKey)
                .orElseThrow(() -> ApiException.unprocessable("MODEL_NOT_FOUND",
                        "El modelo asociado a esta version ya no existe en el catalogo."));
    }

    /** Resuelve la version de modelo a partir de lo que envio el cliente. */
    private ModelVersion resolveModelVersion(CreatePredictionRequest request) {
        if (request.modelVersionId() != null) {
            return modelVersionRepository.findById(request.modelVersionId())
                    .orElseThrow(() -> ApiException.notFound("MODEL_VERSION_NOT_FOUND",
                            "La version de modelo indicada no existe."));
        }
        MlModel model = modelRepository.findById(request.modelId())
                .orElseThrow(() -> ApiException.notFound("MODEL_NOT_FOUND",
                        "El modelo indicado no existe."));
        // Un modelo sin campeon no se puede usar: R-24 exige que la eleccion se
        // haya hecho por validacion, y eso es precisamente lo que define al campeon.
        return modelVersionRepository.findByModelIdAndChampionTrue(model.getId())
                .orElseThrow(() -> ApiException.unprocessable("NO_CHAMPION_VERSION",
                        "El modelo indicado no tiene ninguna version campeon seleccionada por validacion."));
    }

    private static int clampLookback(Integer lookbackDays) {
        if (lookbackDays == null) {
            return DEFAULT_LOOKBACK;
        }
        return Math.min(Math.max(lookbackDays, 1), MAX_LOOKBACK);
    }

    /**
     * Fecha objetivo de la respuesta del servicio ML.
     *
     * <p>Si el servicio no la envia, o envia algo que no es una fecha ISO, se usa
     * la solicitada. Un 502 con un codigo estable informa mejor que una excepcion
     * de parseo sin traducir.
     */
    private static LocalDate targetDateFrom(JsonNode response, LocalDate fallback) {
        JsonNode value = response.get("target_date");
        if (value == null || value.isNull()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.asText());
        } catch (RuntimeException ex) {
            log.warn("El servicio ML devolvio una fecha objetivo ilegible: {}", value.asText());
            return fallback;
        }
    }

    private static Prediction.Direction direction(String value) {
        try {
            return Prediction.Direction.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Prediction.Direction.FLAT;
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            throw ApiException.badGateway("ML_INVALID_NUMERIC",
                    "El servicio de ML devolvio un valor numerico invalido.");
        }
    }

    private static Map<String, Object> jsonToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        return map;
    }

    private PredictionResponse toResponse(Prediction prediction, Map<Long, String> nameMemo) {
        Long modelId = null;
        String modelName = null;
        if (prediction.getModelVersionId() != null) {
            var version = modelVersionRepository.findById(prediction.getModelVersionId());
            if (version.isPresent()) {
                modelId = version.get().getModelId();
                if (modelId != null) {
                    modelName = nameMemo.computeIfAbsent(modelId, key ->
                            modelRepository.findById(key).map(MlModel::getModelKey).orElse(null));
                }
            }
        }
        return new PredictionResponse(
                Ids.of(prediction.getId()),
                Ids.of(modelId),
                modelName,
                Ids.of(prediction.getModelVersionId()),
                prediction.getSymbol(),
                prediction.getTargetDate(),
                prediction.getPredictedClose(),
                prediction.getPredictedDirection(),
                prediction.getActualClose(),
                prediction.getStatus(),
                prediction.getConfidence(),
                Ids.of(prediction.getDatasetVersionId()),
                prediction.getArtifactSha256(),
                prediction.getConfigSha256(),
                prediction.getSeed(),
                prediction.getTrace() == null ? Map.of() : prediction.getTrace(),
                Ids.of(prediction.getRequestedBy()),
                prediction.getRequestId(),
                prediction.getCreatedAt(),
                DISCLAIMER);
    }

    /**
     * Cuerpo de creacion. Acepta identificadores publicados como cadena o como
     * numero: los dos formatos traveling por la API son el mismo valor (R-38).
     */
    public record CreatePredictionRequest(
            Long modelId,
            Long modelVersionId,
            String symbol,
            LocalDate targetDate,
            Integer lookbackDays
    ) {
        public CreatePredictionRequest {
            if (modelId == null && modelVersionId == null) {
                throw ApiException.badRequest("MISSING_MODEL",
                        "Debe indicar el modelo (modelId) o la version de modelo (modelVersionId).");
            }
        }
    }

    /**
     * Contrato publico de una prediccion.
     *
     * <p>Los identificadores se publican como cadenas opacas ({@link Ids}) y se
     * incluye siempre el aviso legal: R-11 exige que el aviso sea visible en la
     * documentacion de la API, no solo en la interfaz.
     */
    public record PredictionResponse(
            String id,
            String modelId,
            String modelName,
            String modelVersionId,
            String symbol,
            LocalDate targetDate,
            BigDecimal predictedClose,
            Prediction.Direction predictedDirection,
            BigDecimal actualClose,
            Prediction.Status status,
            BigDecimal confidence,
            String datasetVersionId,
            String artifactSha256,
            String configSha256,
            Integer seed,
            Map<String, Object> trace,
            String requestedBy,
            String requestId,
            java.time.Instant createdAt,
            String disclaimer
    ) {
    }
}
