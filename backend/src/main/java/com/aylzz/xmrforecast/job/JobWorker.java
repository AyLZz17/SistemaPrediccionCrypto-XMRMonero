package com.aylzz.xmrforecast.job;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.config.AppProperties;
import com.aylzz.xmrforecast.ml.MlServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumidor de la cola de trabajos.
 *
 * <p>Corre dentro del backend porque el trabajo encolado necesita el contexto de
 * la aplicacion (modelos, datasets, trazabilidad) y porque el servicio ML solo
 * expone inferencia por HTTP: separarlo en un proceso mas exigiria duplicar las
 * credenciales y la conexion con la base de datos a cambio de nada.
 *
 * <p>Tres responsabilidades:
 * <ol>
 *   <li><b>Ejecutar</b>: reclama trabajos PENDING y los lleva a un estado terminal.</li>
 *   <li><b>Reintentar</b>: un fallo transitorio devuelve el trabajo a PENDING
 *       mientras queden intentos; agotados los intentos, pasa a FAILED. No se
 *       reintenta un error de negocio, que no mejorara con un segundo intento.</li>
 *   <li><b>Recuperar</b>: un trabajo en RUNNING cuyo latido es antiguo se
 *       devuelve a PENDING. Es lo que hace que un reinicio del proceso no deje
 *       trabajos colgados para siempre.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "app.jobs.worker-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int BATCH_SIZE = 5;
    private static final long STALL_BACKOFF_MS = 2_000L;

    private final JobRepository jobRepository;
    private final JobQueue queue;
    private final ExperimentRunLink runLink;
    private final MlServiceClient mlClient;
    private final AuditService auditService;
    private final AppProperties properties;

    public JobWorker(JobRepository jobRepository,
                     JobQueue queue,
                     ExperimentRunLink runLink,
                     MlServiceClient mlClient,
                     AuditService auditService,
                     AppProperties properties) {
        this.jobRepository = jobRepository;
        this.queue = queue;
        this.runLink = runLink;
        this.mlClient = mlClient;
        this.auditService = auditService;
        this.properties = properties;
    }

    /**
     * Barrido periodico. El retardo inicial aleatorio evita que todas las
     * instancias del backend reclamen la cola en el mismo milisegundo tras un
     * despliegue simultaneo.
     */
    @Scheduled(initialDelayString = "${app.jobs.worker-initial-delay-ms:5000}",
            fixedDelayString = "${app.jobs.poll-interval-ms:15000}")
    public void poll() {
        recoverStalledJobs();
        List<Job> pending = jobRepository.findPending(Job.JobStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        int claimed = 0;
        for (Job candidate : pending) {
            if (claimed >= BATCH_SIZE) {
                break;
            }
            if (queue.claim(candidate.getId())) {
                claimed++;
                execute(candidate.getId());
            }
        }
    }

    /**
     * Recupera los trabajos colgados. La operacion vive en {@link JobQueue} porque
     * Spring AOP es basado en proxy: una llamada {@code this.recoverStalledJobs()}
     * desde {@link #poll()} NO pasaria por el proxy y su {@code @Transactional}
     * no se aplicaria (documentacion oficial de Spring Framework, "Understanding
     * AOP proxies"). Delegando en otro bean, la llamada sale por el proxy y la
     * transaccion existe de verdad.
     */
    private void recoverStalledJobs() {
        long timeout = properties.jobs().heartbeatTimeoutSeconds();
        queue.recoverStalled(Instant.now().minusSeconds(timeout));
    }

    private void execute(Long jobId) {
        Job job;
        try {
            job = jobRepository.findById(jobId).orElse(null);
        } catch (RuntimeException ex) {
            log.error("No se pudo leer el trabajo {}: {}", jobId, ex.getMessage());
            return;
        }
        if (job == null) {
            return;
        }
        try {
            switch (job.getType()) {
                case "PREDICT" -> runPrediction(job);
                case "INGEST", "BACKFILL" -> runIngest(job);
                case "TRAIN" -> runTraining(job);
                default -> fail(job, "UNSUPPORTED_JOB_TYPE",
                        "Tipo de trabajo no soportado por el worker: " + job.getType());
            }
        } catch (ApiException ex) {
            // Un 5xx del servicio ML es transitorio y merece otro intento; un 4xx
            // es un error de negocio y reintentarlo solo repetiria el mismo fallo.
            if (isTransient(ex.getStatus())) {
                log.warn("Fallo transitorio en el trabajo {}: {}", job.getId(), ex.getCode());
                retryOrFail(job, ex.getCode() + ": " + ex.getMessage());
            } else {
                fail(job, ex.getCode(), ex.getMessage());
            }
            return;
        } catch (RuntimeException ex) {
            log.warn("Fallo el trabajo {} ({}): {}", job.getId(), job.getType(), ex.getMessage());
            retryOrFail(job, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return;
        }

        // `complete` SOLO si el trabajo sigue vivo. Varias ramas lo cierran ellas
        // mismas (`runTraining` marca TRAINING_NOT_EXPOSED, `runPrediction` falla si
        // no indica modelo, el `default` marca UNSUPPORTED_JOB_TYPE) y llamarlos
        // sin comprobar antes convertia un FAILED honesto en un COMPLETED mentiroso:
        // la corrida del experimento se daba por terminada sin que hubiera
        // entrenado nada (R-21).
        if (!job.isTerminal()) {
            complete(job);
        }
    }

    /** 502, 503 y 504 son fallos de transporte o indisponibilidad: reintentables. */
    private static boolean isTransient(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    /** Inferencia contra el servicio ML. Es transitoria si el servicio no responde. */
    private void runPrediction(Job job) {
        Map<String, Object> payload = job.getPayload() == null ? Map.of() : job.getPayload();
        String modelKey = String.valueOf(payload.getOrDefault("modelKey", ""));
        if (modelKey.isBlank()) {
            fail(job, "MISSING_MODEL_KEY", "El trabajo no indica que modelo usar.");
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model_key", modelKey);
        request.put("version", String.valueOf(payload.getOrDefault("version", "")));
        request.put("symbol", String.valueOf(payload.getOrDefault("symbol", "XMR-USD")));
        request.put("lookback_days", payload.getOrDefault("lookbackDays", 30));
        JsonNode response = mlClient.predict(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetDate", response.path("target_date").asText(""));
        result.put("predictedClose", response.path("predicted_close").asText(""));
        result.put("predictedDirection", response.path("predicted_direction").asText(""));
        job.setResult(result);
    }

    /**
     * La ingesta de velas es trabajo del pipeline de ML ejecutado por CLI, no de
     * la API. Se marca como completada con resultado explicito en lugar de
     * fingir que se ingirieron datos (R-21: no se inventan resultados).
     */
    private void runIngest(Job job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delegatedTo", "ml-service CLI (python -m app.ml.pipelines.ingest)");
        result.put("executed", false);
        job.setResult(result);
    }

    /**
     * El entrenamiento no se expone por HTTP a proposito: es una operacion larga
     * y reproducible que se ejecuta por CLI con una configuracion versionada. La
     * corrida se marca como FAILED con un mensaje que dice exactamente por que,
     * en lugar de quedarse en RUNNING para siempre.
     */
    private void runTraining(Job job) {
        fail(job, "TRAINING_NOT_EXPOSED",
                "El entrenamiento se ejecuta por CLI sobre una configuracion versionada "
                        + "(configs/*.yaml). Este endpoint solo encola y registra la corrida.");
    }

    private void retryOrFail(Job job, String reason) {
        job.setAttempts(job.getAttempts() + 1);
        if (job.getAttempts() < job.getMaxAttempts()) {
            job.setStatus(Job.JobStatus.PENDING);
            job.setHeartbeatAt(Instant.now());
            jobRepository.save(job);
            auditService.record(null, "SYSTEM", "JOB_RETRY", "Job",
                    String.valueOf(job.getId()), AuditEvent.Outcome.FAILURE,
                    Map.of("attempts", job.getAttempts(), "reason", safe(reason)));
            log.info("Trabajo {} reencolado (intento {}/{})", job.getId(),
                    job.getAttempts(), job.getMaxAttempts());
            sleepBackoff();
            return;
        }
        fail(job, "MAX_ATTEMPTS_EXCEEDED", safe(reason));
    }

    private void fail(Job job, String code, String reason) {
        String message = truncate(reason == null ? code : code + ": " + reason);
        job.setStatus(Job.JobStatus.FAILED);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        job.setProgressPercent(100);
        jobRepository.save(job);
        // La corrida asociada pasa a FAILED tambien ella. Sin esta llamada el
        // experimento se quedaba en RUNNING para siempre, con un trabajo muerto:
        // el estado mentiroso que R-21 prohibe.
        runLink.onJobFailed(job, message);
        auditService.record(null, "SYSTEM", "JOB_FAILED", "Job",
                String.valueOf(job.getId()), AuditEvent.Outcome.FAILURE,
                Map.of("code", code, "reason", safe(reason)));
    }

    private void complete(Job job) {
        job.setStatus(Job.JobStatus.COMPLETED);
        job.setProgressPercent(100);
        job.setFinishedAt(Instant.now());
        job.setHeartbeatAt(Instant.now());
        jobRepository.save(job);
        runLink.onJobCompleted(job);
        auditService.record(null, "SYSTEM", "JOB_COMPLETED", "Job",
                String.valueOf(job.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("jobKey", job.getJobKey(), "type", job.getType()));
    }

    private static String safe(String reason) {
        return reason == null ? "" : reason;
    }

    private static String truncate(String value) {
        return value.length() <= 900 ? value : value.substring(0, 900);
    }

    private static void sleepBackoff() {
        try {
            Thread.sleep(STALL_BACKOFF_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Puente con las corridas de experimento. Se declara como interfaz para que el
     * worker no dependa del modulo de experimentos y siga siendo testeable.
     *
     * <p>Hay un metodo por desenlace y no solo uno de "completado": si el puente
     * solo escuchara el exito, una corrida fallida dejaria el experimento en
     * RUNNING para siempre, que es justo el estado mentiroso que este puente existe
     * para evitar.
     */
    public interface ExperimentRunLink {
        void onJobCompleted(Job job);

        /** El trabajo termino en un estado distinto de COMPLETED. */
        void onJobFailed(Job job, String reason);
    }
}