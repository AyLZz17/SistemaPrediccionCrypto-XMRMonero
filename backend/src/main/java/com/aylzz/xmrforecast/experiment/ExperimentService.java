package com.aylzz.xmrforecast.experiment;

import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.dataset.DatasetVersionRepository;
import com.aylzz.xmrforecast.job.Job;
import com.aylzz.xmrforecast.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experimentos y sus corridas.
 *
 * <p>Crear un experimento no arranca nada: solo persiste la hipotesis, la
 * configuracion y el dataset versionado. Lanzar una corrida encola un trabajo
 * asincrono, de modo que la API nunca se bloquea por un entrenamiento.
 */
@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);
    private static final int MAX_PAGE_SIZE = 200;

    /** R-08: los modelos estocasticos se evaluan con al menos 5 semillas. */
    private static final int MIN_SEEDS = 5;
    private static final int MAX_SEEDS = 32;
    private static final List<Integer> DEFAULT_SEEDS = List.of(42, 43, 44, 45, 46);

    private final ExperimentRepository experimentRepository;
    private final ExperimentRunRepository runRepository;
    private final DatasetVersionRepository datasetRepository;
    private final JobRepository jobRepository;
    private final AuditService auditService;

    public ExperimentService(ExperimentRepository experimentRepository,
                             ExperimentRunRepository runRepository,
                             DatasetVersionRepository datasetRepository,
                             JobRepository jobRepository,
                             AuditService auditService) {
        this.experimentRepository = experimentRepository;
        this.runRepository = runRepository;
        this.datasetRepository = datasetRepository;
        this.jobRepository = jobRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExperimentResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<Experiment> result = experimentRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.of(result, ExperimentResponse::from);
    }

    @Transactional(readOnly = true)
    public ExperimentResponse get(Long id) {
        return ExperimentResponse.from(require(id));
    }

    /**
     * Crea un experimento.
     *
     * <p>El cliente no envía codigo ni configuracion: se derivan. El codigo se
     * genera a partir del nombre para que sea legible, y la configuracion se
     * guarda como YAML minimo con la particion cronologica 70/15/15 (D-05) y las
     * semillas por defecto, de modo que lo registrado sea siempre coherente con
     * las reglas del proyecto aunque el cliente no sepa declararlas.
     */
    @Transactional
    public ExperimentResponse create(CreateExperimentRequest request, Long userId, String actorRole) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("INVALID_NAME", "El experimento necesita un nombre.");
        }
        Long datasetVersionId = resolveDataset(request.datasetId());
        Experiment.TaskType task = request.task() == Experiment.TaskType.DIRECTION
                ? Experiment.TaskType.DIRECTION : Experiment.TaskType.REGRESSION;

        String code = uniqueCode(name);
        String configYaml = defaultConfig(task);

        Experiment experiment = new Experiment();
        experiment.setCode(code);
        experiment.setName(name);
        experiment.setDescription(request.description());
        experiment.setHypothesis(request.hypothesis());
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setTaskType(task);
        experiment.setConfigYaml(configYaml);
        experiment.setConfigSha256(sha256(configYaml));
        experiment.setDatasetVersionId(datasetVersionId);
        experiment.setCreatedBy(userId);
        experiment.setCreatedAt(Instant.now());
        experiment.setUpdatedAt(Instant.now());

        Experiment saved = experimentRepository.save(experiment);
        auditService.success(userId, actorRole, "EXPERIMENT_CREATED", "Experiment",
                Ids.of(saved.getId()), Map.of("code", code, "task", task.name()));
        return ExperimentResponse.from(saved);
    }

    /**
     * Registra una corrida y encola el trabajo de entrenamiento.
     *
     * <p>La clave de idempotencia es determinista a partir de
     * (experimento, runKey): reenviar la misma peticion devuelve el trabajo ya
     * existente en lugar de encolar un segundo entrenamiento identico.
     */
    @Transactional
    public RunStartResponse startRun(Long experimentId, StartRunRequest request,
                                    Long userId, String actorRole) {
        Experiment experiment = require(experimentId);
        if (experiment.getStatus() == Experiment.ExperimentStatus.CANCELLED) {
            throw ApiException.conflict("EXPERIMENT_CANCELLED",
                    "El experimento esta cancelado y no admite nuevas corridas.");
        }

        String runKey = request.runKey() == null || request.runKey().isBlank()
                ? "run-" + System.currentTimeMillis() : request.runKey().trim();
        if (runRepository.findByExperimentIdAndRunKey(experiment.getId(), runKey).isPresent()) {
            throw ApiException.conflict("RUN_ALREADY_EXISTS",
                    "Ya existe una corrida con ese identificador en este experimento.");
        }

        List<Integer> seeds = normalizeSeeds(request.seeds());

        ExperimentRun run = new ExperimentRun();
        run.setExperimentId(experiment.getId());
        run.setRunKey(runKey);
        run.setStatus(ExperimentRun.RunStatus.PENDING);
        // El experimento y la corrida declaran el mismo dominio de tareas, pero
        // cada uno con su propio enum: se traduce de forma explicita para que un
        // valor nuevo en uno no se copie por accidente al otro.
        run.setTaskType(experiment.getTaskType() == Experiment.TaskType.DIRECTION
                ? ExperimentRun.TaskType.DIRECTION : ExperimentRun.TaskType.REGRESSION);
        run.setSeeds(seeds);
        run.setCreatedBy(userId);
        run.setCreatedAt(Instant.now());
        ExperimentRun savedRun = runRepository.save(run);

        Job job = enqueueTraining(experiment, savedRun, userId);
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdatedAt(Instant.now());
        experimentRepository.save(experiment);

        auditService.success(userId, actorRole, "EXPERIMENT_RUN_STARTED", "ExperimentRun",
                Ids.of(savedRun.getId()), Map.of("experiment", experiment.getCode(),
                        "job", job.getJobKey(), "seeds", seeds.size()));

        return new RunStartResponse(RunResponse.from(savedRun), JobSummary.from(job));
    }

    @Transactional(readOnly = true)
    public PageResponse<RunResponse> listRuns(Long experimentId, int page, int size) {
        require(experimentId);
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<ExperimentRun> result = runRepository.findAllByExperimentIdOrderByCreatedAtDesc(
                experimentId, pageable);
        return PageResponse.of(result, RunResponse::from);
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(Long experimentId, Long runId) {
        require(experimentId);
        ExperimentRun run = runRepository.findById(runId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "La corrida no existe."));
        if (!run.getExperimentId().equals(experimentId)) {
            throw ApiException.notFound("RUN_NOT_FOUND", "La corrida no pertenece a este experimento.");
        }
        return RunResponse.from(run);
    }

    /**
     * Ancho maximo de {@code jobs.idempotency_key} y {@code jobs.job_key}.
     *
     * <p>La clave de idempotencia se derivaba antes de la concatenacion literal
     * ("train:" + code + ":" + runKey): {@code experiments.code} admite 64
     * caracteres y {@code experiment_runs.run_key} otros 64, de modo que la clave
     * podia medir hasta 135 y {@code job_key} hasta 135 tambien, frente a un
     * {@code VARCHAR(128)} y un {@code VARCHAR(80)}. Con un codigo largo el alta
     * terminaba en un error de base de datos (500), no en un 400.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 128;
    private static final int MAX_JOB_KEY = 80;

    /** Encola el trabajo de entrenamiento. La clave de idempotencia es estable. */
    private Job enqueueTraining(Experiment experiment, ExperimentRun run, Long userId) {
        // Se hashea en lugar de concatenar: la clave sigue siendo determinista
        // (mismo experimento + misma corrida = mismo trabajo) y su longitud deja de
        // depender de los limites de dos columnas ajenas.
        String idempotencyKey = truncate("train:" + sha256(
                experiment.getId() + ":" + run.getRunKey()), MAX_IDEMPOTENCY_KEY);
        var existing = jobRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        Job job = new Job();
        job.setJobKey(truncate("train-" + experiment.getCode() + "-" + run.getRunKey(),
                MAX_JOB_KEY));
        job.setType("TRAIN");
        job.setStatus(Job.JobStatus.PENDING);
        job.setIdempotencyKey(idempotencyKey);
        job.setPayload(Map.of(
                "experimentId", String.valueOf(experiment.getId()),
                "experimentCode", experiment.getCode(),
                "runId", String.valueOf(run.getId()),
                "runKey", run.getRunKey(),
                "datasetVersionId", String.valueOf(experiment.getDatasetVersionId()),
                "task", experiment.getTaskType().name(),
                "seeds", run.getSeeds().stream().map(String::valueOf).toList(),
                "configSha256", experiment.getConfigSha256()));
        job.setMaxAttempts(3);
        job.setCreatedBy(userId);
        return jobRepository.save(job);
    }

    private Long resolveDataset(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            // Sin dataset explicito se usa el mas reciente, para que el cliente
            // pueda crear un experimento sin tener que recordar el id a mano.
            return datasetRepository.findAll(PageRequest.of(0, 1,
                            org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                    .stream()
                    .findFirst()
                    .map(dataset -> dataset.getId())
                    .orElseThrow(() -> ApiException.unprocessable("NO_DATASET_VERSION",
                            "No hay ninguna version de dataset registrada; registra una antes de crear el experimento."));
        }
        Long id = QueryParams.id(datasetId);
        if (datasetRepository.findById(id).isEmpty()) {
            throw ApiException.notFound("DATASET_NOT_FOUND", "La version de dataset no existe.");
        }
        return id;
    }

    /** R-08: al menos 5 semillas para modelos estocasticos, con tope superior. */
    private static List<Integer> normalizeSeeds(List<Integer> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_SEEDS;
        }
        if (requested.size() < MIN_SEEDS) {
            throw ApiException.badRequest("INSUFFICIENT_SEEDS",
                    "Se requieren al menos " + MIN_SEEDS + " semillas para modelos estocasticos (R-08).");
        }
        if (requested.size() > MAX_SEEDS) {
            throw ApiException.badRequest("TOO_MANY_SEEDS",
                    "Se admiten como maximo " + MAX_SEEDS + " semillas por corrida.");
        }
        return requested.stream().distinct().toList();
    }

    /**
     * Ancho de {@code experiments.code} (VARCHAR(64)).
     *
     * <p>Se recorta <em>antes</em> de buscar duplicados, no despues: si se
     * comprobaran nombres largos en su forma completa y se truncara al final, dos
     * nombres distintos podian producir el mismo codigo de 64 caracteres y el
     * segundo chocaba con la restriccion unica, respondiendole al cliente un
     * {@code CONSTRAINT_VIOLATION} en lugar de un codigo distinto y valido.
     */
    private static final int MAX_EXPERIMENT_CODE = 64;

    private String uniqueCode(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "exp";
        }
        // Recorte previo: la busqueda de unicidad se hace sobre el mismo texto
        // que se acaba persistiendo.
        String prefix = base.length() <= MAX_EXPERIMENT_CODE - 4
                ? base : base.substring(0, MAX_EXPERIMENT_CODE - 4);
        String candidate = prefix;
        int suffix = 2;
        while (experimentRepository.existsByCode(candidate)) {
            candidate = prefix + "-" + suffix++;
        }
        return candidate;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Configuracion minima coherente con D-05 y R-08. */
    private static String defaultConfig(Experiment.TaskType task) {
        return """
                # Configuracion por defecto generada por la API (D-05, R-08).
                # No se deduce del cliente: son las reglas del proyecto.
                task: %s
                split:
                  method: chronological
                  train: 0.70
                  validation: 0.15
                  test: 0.15
                shuffle: false
                scaler:
                  type: minmax
                  fit_on: train_only
                seeds: [42, 43, 44, 45, 46]
                selection:
                  on: validation
                  never_on: test
                """.formatted(task.name().toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", ex);
        }
    }

    private Experiment require(Long id) {
        return experimentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("EXPERIMENT_NOT_FOUND",
                        "El experimento no existe."));
    }

    /** Cuerpo de alta. */
    public record CreateExperimentRequest(
            String name,
            String description,
            String hypothesis,
            Experiment.TaskType task,
            String datasetId
    ) {
    }

    /** Cuerpo de arranque de corrida. */
    public record StartRunRequest(String runKey, List<Integer> seeds) {
    }

    /** Respuesta de arranque: la corrida y el trabajo asincrono encolado. */
    public record RunStartResponse(RunResponse run, JobSummary job) {
    }

    /** Datos minimos del trabajo encolado, para que la UI pueda seguirlo. */
    public record JobSummary(String id, String jobKey, String type, String status) {
        static JobSummary from(Job job) {
            return new JobSummary(Ids.of(job.getId()), job.getJobKey(), job.getType(),
                    job.getStatus().name());
        }
    }

    /**
     * Experimento publicado.
     *
     * <p>El estado se traduce al vocabulario del cliente: {@code DRAFT} se
     * presenta como {@code PENDING} y {@code COMPLETED} como {@code SUCCEEDED}.
     * Son los mismos estados con otro nombre; traducirlos aqui evita que cada
     * cliente tenga que conocer los dos vocabularios y dudar entre ellos.
     */
    public record ExperimentResponse(
            String id,
            String code,
            String name,
            String description,
            String status,
            String task,
            String datasetId,
            String configSha256,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        static ExperimentResponse from(Experiment experiment) {
            return new ExperimentResponse(
                    Ids.of(experiment.getId()),
                    experiment.getCode(),
                    experiment.getName(),
                    experiment.getDescription(),
                    publicStatus(experiment.getStatus()),
                    experiment.getTaskType().name(),
                    Ids.of(experiment.getDatasetVersionId()),
                    experiment.getConfigSha256(),
                    Ids.of(experiment.getCreatedBy()),
                    experiment.getCreatedAt(),
                    experiment.getUpdatedAt());
        }

        static String publicStatus(Experiment.ExperimentStatus status) {
            return switch (status) {
                case DRAFT -> "PENDING";
                case COMPLETED -> "SUCCEEDED";
                case RUNNING, FAILED, CANCELLED -> status.name();
            };
        }
    }

    /** Corrida publicada, con la semilla usada y el mensaje de fallo si lo hubo. */
    public record RunResponse(
            String id,
            String experimentId,
            String runId,
            String status,
            String task,
            List<Integer> seeds,
            Instant startedAt,
            Instant finishedAt,
            String message,
            Instant createdAt
    ) {
        static RunResponse from(ExperimentRun run) {
            return new RunResponse(
                    Ids.of(run.getId()),
                    Ids.of(run.getExperimentId()),
                    run.getRunKey(),
                    publicRunStatus(run.getStatus()),
                    run.getTaskType().name(),
                    run.getSeeds() == null ? List.of() : List.copyOf(run.getSeeds()),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getErrorMessage(),
                    run.getCreatedAt());
        }

        static String publicRunStatus(ExperimentRun.RunStatus status) {
            return switch (status) {
                case PENDING -> "PENDING";
                case COMPLETED -> "SUCCEEDED";
                case RUNNING, FAILED, CANCELLED -> status.name();
            };
        }
    }
}
