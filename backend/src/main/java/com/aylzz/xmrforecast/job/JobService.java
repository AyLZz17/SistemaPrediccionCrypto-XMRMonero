package com.aylzz.xmrforecast.job;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cola de trabajos asincronos.
 *
 * <p>Los trabajos largos (entrenamiento, inferencia masiva, ingesta) nunca
 * bloquean una peticion HTTP: se encolan en la tabla {@code jobs} y los consume
 * el {@link JobWorker}. La garantia de no duplicado es la columna
 * {@code idempotency_key}, unica en la base de datos: dos clientes que piden lo
 * mismo a la vez no producen dos trabajos, porque el segundo recibe el primero.
 */
@Service
public class JobService {

    /** Tipos de trabajo reconocidos. Un tipo desconocido se rechaza, no se ignora. */
    public static final List<String> TYPES = List.of("INGEST", "TRAIN", "PREDICT", "BACKFILL");

    private static final int MAX_PAGE_SIZE = 200;

    private final JobRepository repository;
    private final AuditService auditService;

    public JobService(JobRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * Lista trabajos visibles para un actor.
     *
     * <p>ADMIN ve la cola completa (necesita supervisela); el resto solo ve lo que
     * encolo. Es aislamiento por propietario con una excepcion administrativa
     * explicita, no una fuga lateral.
     */
    @Transactional(readOnly = true)
    public PageResponse<JobResponse> list(Long userId, boolean admin, int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<Job> result = admin
                ? repository.findAllByOrderByQueuedAtDesc(pageable)
                : repository.findAllByCreatedByOrderByQueuedAtDesc(userId, pageable);
        return PageResponse.of(result, JobResponse::from);
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id, Long userId, boolean admin) {
        Job job = require(id);
        if (!admin && (job.getCreatedBy() == null || !job.getCreatedBy().equals(userId))) {
            throw ApiException.notFound("JOB_NOT_FOUND", "El trabajo no existe.");
        }
        return JobResponse.from(job);
    }

    /**
     * Encola un trabajo. Si ya existe la clave de idempotencia, devuelve el
     * trabajo existente: es el mecanismo que garantiza que un doble clic, una
     * reentrega o un reintento no dupliquen el trabajo.
     */
    @Transactional
    public JobResponse enqueue(String type, String idempotencyKey, Map<String, Object> payload,
                               Long userId, String actorRole) {
        String normalized = normalizeType(type);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                return JobResponse.from(existing.get());
            }
        }
        Job job = new Job();
        job.setJobKey(buildJobKey(normalized));
        job.setType(normalized);
        job.setStatus(Job.JobStatus.PENDING);
        job.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.trim());
        job.setPayload(payload == null ? Map.of() : payload);
        job.setCreatedBy(userId);
        Job saved = repository.save(job);
        auditService.success(userId, actorRole, "JOB_ENQUEUED", "Job",
                Ids.of(saved.getId()), Map.of("type", normalized, "jobKey", saved.getJobKey()));
        return JobResponse.from(saved);
    }

    /**
     * Cancela un trabajo.
     *
     * <p>Solo se puede cancelar lo que aun no termino. Un trabajo ya completado o
     * fallido devuelve 409: informar "cancelado" de un trabajo que ya corrio
     * seria mentir sobre el estado real del sistema.
     */
    @Transactional
    public JobResponse cancel(Long id, Long userId, boolean admin, String actorRole) {
        Job job = require(id);
        if (!admin && (job.getCreatedBy() == null || !job.getCreatedBy().equals(userId))) {
            throw ApiException.notFound("JOB_NOT_FOUND", "El trabajo no existe.");
        }
        if (job.isTerminal()) {
            throw ApiException.conflict("JOB_ALREADY_FINISHED",
                    "El trabajo ya termino con estado " + job.getStatus() + " y no se puede cancelar.");
        }
        job.setStatus(Job.JobStatus.CANCELLED);
        job.setFinishedAt(Instant.now());
        repository.save(job);
        auditService.record(userId, actorRole, "JOB_CANCELLED", "Job",
                Ids.of(job.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("jobKey", job.getJobKey(), "previousStatus", "PENDING_OR_RUNNING"));
        return JobResponse.from(job);
    }

    /** Resumen de la cola, para el panel de operaciones. */
    @Transactional(readOnly = true)
    public QueueSummary summary(boolean admin, Long userId) {
        return new QueueSummary(
                repository.countByStatus(Job.JobStatus.PENDING),
                repository.countByStatus(Job.JobStatus.RUNNING),
                repository.countByStatus(Job.JobStatus.FAILED),
                admin ? null : userId);
    }

    private Job require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("JOB_NOT_FOUND", "El trabajo no existe."));
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw ApiException.badRequest("INVALID_JOB_TYPE", "Debe indicar el tipo de trabajo.");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw ApiException.badRequest("INVALID_JOB_TYPE",
                    "Tipo de trabajo desconocido. Use uno de: " + String.join(", ", TYPES) + ".");
        }
        return normalized;
    }

    /** Clave legible y unica: tipo + marca temporal + sufijo aleatorio. */
    private static String buildJobKey(String type) {
        String key = type.toLowerCase(Locale.ROOT) + "-" + System.currentTimeMillis()
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return key.length() <= 80 ? key : key.substring(0, 80);
    }

    /**
     * Trabajo publicado. El estado se traduce al vocabulario del cliente
     * ({@code PENDING} -> {@code QUEUED}, {@code COMPLETED} -> {@code SUCCEEDED}).
     */
    public record JobResponse(
            String id,
            String type,
            String status,
            Integer progress,
            String createdBy,
            String jobKey,
            String message,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        static JobResponse from(Job job) {
            return new JobResponse(
                    Ids.of(job.getId()),
                    job.getType(),
                    publicStatus(job.getStatus()),
                    job.getProgressPercent(),
                    Ids.of(job.getCreatedBy()),
                    job.getJobKey(),
                    job.getErrorMessage(),
                    job.getQueuedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt());
        }

        static String publicStatus(Job.JobStatus status) {
            return switch (status) {
                case PENDING -> "QUEUED";
                case COMPLETED -> "SUCCEEDED";
                case RUNNING, FAILED, CANCELLED -> status.name();
            };
        }
    }

    /** Recuento de la cola por estado. */
    public record QueueSummary(long pending, long running, long failed, Long scopedToUser) {
    }
}
