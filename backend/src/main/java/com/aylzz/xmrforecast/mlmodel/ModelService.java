package com.aylzz.xmrforecast.mlmodel;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de modelos y promocion de campeon.
 *
 * <p>La promocion es el punto mas sensible de todo el subsistema de ML, asi que
 * no es un simple {@code UPDATE}: antes de aceptar el cambio se comprueban los
 * gates de R-28 (integridad del artefacto, procedencia del dataset y ausencia de
 * seleccion por test). Un fallo de cualquiera de ellos devuelve 422 y deja el
 * campeon anterior intacto.
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final MlModelRepository modelRepository;
    private final ModelVersionRepository versionRepository;
    private final AuditService auditService;

    public ModelService(MlModelRepository modelRepository,
                        ModelVersionRepository versionRepository,
                        AuditService auditService) {
        this.modelRepository = modelRepository;
        this.versionRepository = versionRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<ModelResponse> listModels(int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<MlModel> result = modelRepository.findAllByOrderByModelKeyAsc(pageable);
        // Los campeones de la pagina se resuelven en una sola consulta. La version
        // anterior llamaba a findByModelIdAndChampionTrue dentro del mapeador: con
        // el tope de 200 modelos por pagina eran 201 consultas, todas devolviendo
        // como mucho cinco filas.
        Map<Long, Long> champions = championVersionIds(result.getContent());
        return PageResponse.of(result, model -> toResponse(model, champions));
    }

    /** Campeon vigente de cada modelo indicado, indexado por id de modelo. */
    private Map<Long, Long> championVersionIds(List<MlModel> models) {
        if (models.isEmpty()) {
            return Map.of();
        }
        List<Long> modelIds = models.stream().map(MlModel::getId).toList();
        Map<Long, Long> champions = new LinkedHashMap<>();
        for (ModelVersion version : versionRepository.findAllByModelIdInAndChampionTrue(modelIds)) {
            champions.put(version.getModelId(), version.getId());
        }
        return champions;
    }

    @Transactional(readOnly = true)
    public List<ModelVersionResponse> listVersions(Long modelId) {
        MlModel model = requireModel(modelId);
        return versionRepository.findAllByModelIdOrderByCreatedAtDesc(model.getId(), Pageable.unpaged())
                .stream().map(version -> ModelVersionResponse.of(version, model.getId())).toList();
    }

    /**
     * Promueve una version a campeon del modelo.
     *
     * <p>Gates aplicados, en orden y todos obligatorios:
     * <ol>
     *   <li>La version pertenece al modelo indicado (no se puede promover con ids cruzados).</li>
     *   <li>Integridad verificada del artefacto (R-28).</li>
     *   <li>Procedencia: la version nace de una corrida registrada (R-28).</li>
     *   <li>No fue seleccionada mirando el conjunto de prueba (R-24).</li>
     * </ol>
     */
    @Transactional
    public ModelVersionResponse promote(Long modelId, Long versionId, Long actorId,
                                        com.aylzz.xmrforecast.security.Role actorRole) {
        MlModel model = requireModel(modelId);
        ModelVersion candidate = versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("MODEL_VERSION_NOT_FOUND",
                        "La version de modelo no existe."));

        if (!candidate.getModelId().equals(model.getId())) {
            // Un id de version de otro modelo no debe confirmar ni desmentir la
            // existencia de esa version: se responde como si no existiera.
            throw ApiException.notFound("MODEL_VERSION_NOT_FOUND",
                    "La version de modelo no pertenece a este modelo.");
        }

        if (!candidate.isIntegrityVerified()) {
            throw ApiException.unprocessable("INTEGRITY_NOT_VERIFIED",
                    "La version no supero la verificacion de integridad del artefacto; no se puede promover.");
        }
        if (candidate.getRunId() == null) {
            throw ApiException.unprocessable("NO_PROVENANCE",
                    "La version no tiene una corrida de entrenamiento asociada; no se puede promover.");
        }
        if (candidate.getSelectedOn() == ModelVersion.SelectedOn.TEST) {
            throw ApiException.unprocessable("SELECTED_ON_TEST",
                    "Un campeon no puede seleccionarse usando el conjunto de prueba (R-24).");
        }

        // Solo puede haber un campeon por modelo: el indice unico parcial de la
        // base de datos lo garantiza, asi que primero se libera el anterior.
        versionRepository.findByModelIdAndChampionTrue(model.getId()).ifPresent(previous -> {
            previous.setChampion(false);
            versionRepository.save(previous);
        });

        candidate.setChampion(true);
        candidate.setSelectedOn(ModelVersion.SelectedOn.MANUAL);
        candidate.setPromotedBy(actorId);
        candidate.setPromotedAt(Instant.now());
        versionRepository.save(candidate);

        auditService.record(actorId, actorRole == null ? null : actorRole.name(), "MODEL_PROMOTED",
                "ModelVersion",
                Ids.of(candidate.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("modelKey", model.getModelKey(),
                        "version", candidate.getVersion(),
                        "artifactSha256", candidate.getArtifactSha256(),
                        "requestId", RequestContext.requestId() == null ? "" : RequestContext.requestId()));

        log.info("Campeon promovido model={} version={} por actor={}",
                model.getModelKey(), candidate.getVersion(), actorId);
        return ModelVersionResponse.of(candidate, model.getId());
    }

    private MlModel requireModel(Long modelId) {
        return modelRepository.findById(modelId)
                .orElseThrow(() -> ApiException.notFound("MODEL_NOT_FOUND",
                        "El modelo no existe."));
    }

    private ModelResponse toResponse(MlModel model, Map<Long, Long> champions) {
        Long championId = champions.get(model.getId());
        return new ModelResponse(
                Ids.of(model.getId()),
                model.getModelKey(),
                model.getFamily(),
                model.getTaskType(),
                model.getDescription(),
                championId == null ? null : Ids.of(championId),
                model.getCreatedAt());
    }

    /**
     * Modelo logico. {@code name} es la clave estable del modelo
     * ({@code lstm_base}, {@code arima}, ...): es lo que el servicio ML entiende
     * como {@code model_key} y lo que la interfaz muestra en el selector.
     */
    public record ModelResponse(
            String id,
            String name,
            MlModel.Family family,
            MlModel.TaskType task,
            String description,
            String championVersionId,
            Instant createdAt
    ) {
    }

    /** Version concreta de un modelo, con su digest de artefacto. */
    public record ModelVersionResponse(
            String id,
            String modelId,
            String version,
            String label,
            boolean isChampion,
            String selectedOn,
            boolean integrityVerified,
            String datasetVersionId,
            String runId,
            String digest,
            Integer seed,
            Instant createdAt
    ) {
        static ModelVersionResponse of(ModelVersion version, Long modelId) {
            return new ModelVersionResponse(
                    Ids.of(version.getId()),
                    Ids.of(modelId),
                    version.getVersion(),
                    version.getVersion(),
                    version.isChampion(),
                    version.getSelectedOn().name(),
                    version.isIntegrityVerified(),
                    Ids.of(version.getDatasetVersionId()),
                    Ids.of(version.getRunId()),
                    version.getArtifactSha256(),
                    version.getSeed(),
                    version.getCreatedAt());
        }
    }
}
