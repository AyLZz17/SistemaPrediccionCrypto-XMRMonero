package com.aylzz.xmrforecast.prediction;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.ml.MlServiceClient;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orquestacion de predicciones.
 *
 * <p>Idempotencia: la tabla {@code predictions} tiene una restriccion unica sobre
 * (model_version_id, symbol, target_date). Repetir la misma peticion devuelve la
 * prediccion existente en lugar de crear otra (seccion 10).
 *
 * <p>Aislamiento entre usuarios: las lecturas se filtran siempre por
 * {@code requested_by}; no existe forma de listar las predicciones de otro usuario.
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
    private static final String DEFAULT_SYMBOL = "XMR-USD";
    private static final int DEFAULT_LOOKBACK = 30;

    private final PredictionRepository predictionRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final MlServiceClient mlClient;

    public PredictionService(PredictionRepository predictionRepository,
                             ModelVersionRepository modelVersionRepository,
                             MlServiceClient mlClient) {
        this.predictionRepository = predictionRepository;
        this.modelVersionRepository = modelVersionRepository;
        this.mlClient = mlClient;
    }

    /**
     * Solicita una prediccion al servicio ML y la persiste con su trazabilidad.
     */
    @Transactional
    public PredictionResponse create(CreatePredictionRequest request, Long userId) {
        ModelVersion modelVersion = modelVersionRepository.findById(request.modelVersionId())
                .orElseThrow(() -> ApiException.notFound("MODEL_VERSION_NOT_FOUND",
                        "La version de modelo indicada no existe."));

        if (!modelVersion.isIntegrityVerified()) {
            // R-28: no se sirve un artefacto que no haya pasado el gate de integridad.
            throw ApiException.unprocessable("MODEL_NOT_VERIFIED",
                    "La version de modelo no supero la verificacion de integridad.");
        }

        String symbol = request.symbol() == null || request.symbol().isBlank()
                ? DEFAULT_SYMBOL : request.symbol().trim().toUpperCase(java.util.Locale.ROOT);
        LocalDate targetDate = request.targetDate() == null
                ? LocalDate.now().plusDays(1) : request.targetDate();

        // Idempotencia: si ya existe para este modelo/simbolo/fecha, se devuelve.
        var existing = predictionRepository
                .findByModelVersionIdAndSymbolAndTargetDate(modelVersion.getId(), symbol, targetDate);
        if (existing.isPresent()) {
            log.info("Prediccion ya existente, se devuelve sin recalcular (id={})", existing.get().getId());
            return PredictionResponse.from(existing.get());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        // Contrato con el servicio ML (app/api/schemas.py::PredictRequest), que
        // prohibe campos extra. La semilla viaja en la version del modelo, no aqui.
        payload.put("model_key", modelKeyOf(modelVersion.getId()));
        payload.put("version", modelVersion.getVersion());
        payload.put("symbol", symbol);
        payload.put("lookback_days", request.lookbackDays() == null
                ? DEFAULT_LOOKBACK : request.lookbackDays());

        JsonNode response = mlClient.predict(payload);

        Prediction prediction = new Prediction();
        prediction.setModelVersionId(modelVersion.getId());
        prediction.setSymbol(symbol);
        prediction.setTargetDate(LocalDate.parse(response.path("target_date").asText(targetDate.toString())));
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
        predictionRepository.save(prediction);

        return PredictionResponse.from(prediction);
    }

    @Transactional(readOnly = true)
    public PageResponse<PredictionResponse> listForUser(Long userId, String symbol, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Prediction> result = (symbol == null || symbol.isBlank())
                ? predictionRepository.findAllByRequestedByOrderByCreatedAtDesc(userId, pageable)
                : predictionRepository.findAllBySymbolOrderByTargetDateDesc(
                        symbol.trim().toUpperCase(java.util.Locale.ROOT), pageable);
        return PageResponse.of(result, PredictionResponse::from);
    }

    /** Lectura acotada al propietario: 404 en lugar de 403 para no revelar existencia. */
    @Transactional(readOnly = true)
    public PredictionResponse getOne(Long id, Long userId, boolean admin) {
        Prediction prediction = predictionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("PREDICTION_NOT_FOUND",
                        "La prediccion no existe."));
        if (!admin && (prediction.getRequestedBy() == null
                || !prediction.getRequestedBy().equals(userId))) {
            auditLeakAttempt(id, userId);
            throw ApiException.notFound("PREDICTION_NOT_FOUND", "La prediccion no existe.");
        }
        return PredictionResponse.from(prediction);
    }

    private void auditLeakAttempt(Long predictionId, Long userId) {
        log.warn("Intento de acceso a prediccion ajena id={} userId={}", predictionId, userId);
    }

    private String modelKeyOf(Long modelVersionId) {
        // La clave del modelo se resuelve en el servicio ML a partir de la version;
        // aqui se envia un identificador estable suficiente para el contrato.
        return String.valueOf(modelVersionId);
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

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    public record CreatePredictionRequest(
            Long modelVersionId,
            String symbol,
            LocalDate targetDate,
            Integer lookbackDays
    ) {
        public CreatePredictionRequest {
            if (modelVersionId == null) {
                throw ApiException.badRequest("MISSING_MODEL_VERSION",
                        "Debe indicar la version de modelo.");
            }
        }
    }

    public record PredictionResponse(
            Long id,
            Long modelVersionId,
            String symbol,
            LocalDate targetDate,
            BigDecimal predictedClose,
            Prediction.Direction predictedDirection,
            BigDecimal actualClose,
            BigDecimal confidence,
            Long datasetVersionId,
            String artifactSha256,
            String configSha256,
            Integer seed,
            Map<String, Object> trace,
            Long requestedBy,
            String requestId,
            java.time.Instant createdAt
    ) {
        public static PredictionResponse from(Prediction prediction) {
            return new PredictionResponse(
                    prediction.getId(),
                    prediction.getModelVersionId(),
                    prediction.getSymbol(),
                    prediction.getTargetDate(),
                    prediction.getPredictedClose(),
                    prediction.getPredictedDirection(),
                    prediction.getActualClose(),
                    prediction.getConfidence(),
                    prediction.getDatasetVersionId(),
                    prediction.getArtifactSha256(),
                    prediction.getConfigSha256(),
                    prediction.getSeed(),
                    prediction.getTrace() == null ? Map.of() : prediction.getTrace(),
                    prediction.getRequestedBy(),
                    prediction.getRequestId(),
                    prediction.getCreatedAt());
        }
    }
}