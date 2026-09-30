package com.aylzz.xmrforecast.job;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Limites transaccionales de la cola de trabajos.
 *
 * <p><strong>Por que existe esta clase y no los metodos directamente en
 * {@link JobWorker}.</strong> Spring AOP es basado en proxy: una llamada
 * {@code this.metodo()} desde dentro de la misma clase es una llamada directa
 * sobre la referencia {@code this}, no sobre el proxy, y por tanto
 * <em>no</em> pasa por los interceptores. La documentacion oficial de Spring
 * Framework lo dice de forma explicita ("this next method invocation is a direct
 * call on the 'this' reference", Spring Framework Reference, "Understanding AOP
 * proxies"). Consecuencia directa: un {@code @Transactional} en un metodo
 * invocado por otro metodo de la misma clase no se aplica.
 *
 * <p>{@link JobWorker#poll()} necesita dos operaciones que deben confirmarse por
 * separado y de forma atomica:
 * <ul>
 *   <li><b>Reclamo</b>: un {@code UPDATE ... WHERE status = PENDING} en su propia
 *       transaccion, para que el resultado sea visible de inmediato para el resto
 *       de instancias.</li>
 *   <li><b>Recuperacion</b>: devolver a {@code PENDING} —o cerrar como
 *       {@code FAILED}— todos los trabajos cuyo latido vencio, con su registro de
 *       auditoria, en una sola transaccion.</li>
 * </ul>
 * Al moverlas a un bean distinto, las llamadas desde el worker salen por el proxy
 * y las anotaciones se aplican de verdad.
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    private static final String SYSTEM_ROLE = "SYSTEM";

    private final JobRepository jobRepository;
    private final AuditService auditService;

    public JobQueue(JobRepository jobRepository, AuditService auditService) {
        this.jobRepository = jobRepository;
        this.auditService = auditService;
    }

    /**
     * Reclamo optimista de un trabajo.
     *
     * <p>La actualizacion condicionada a {@code status = PENDING} es la garantia de
     * que dos instancias no ejecuten el mismo trabajo: la segunda actualizacion
     * afecta a cero filas y devuelve {@code false}.
     *
     * <p>{@code REQUIRES_NEW} para que el reclamo se confirme con independencia del
     * trabajo que el worker ejecute a continuacion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long jobId) {
        return jobRepository.claimIfPending(jobId, Job.JobStatus.PENDING, Job.JobStatus.RUNNING) > 0;
    }

    /**
     * Recuperacion tras reinicio.
     *
     * <p>Un trabajo en {@code RUNNING} cuyo latido es antiguo no tiene a nadie
     * ejecutandolo: el proceso que lo ejecutaba murio. Se devuelve a
     * {@code PENDING} si le quedan intentos; si no, se cierra como {@code FAILED}
     * para no dejar abierta una promesa que nadie va a cumplir.
     *
     * @return numero de trabajos recuperados (reencolados o cerrados).
     */
    @Transactional
    public int recoverStalled(Instant cutoff) {
        List<Job> stalled = jobRepository.findStalled(Job.JobStatus.RUNNING, cutoff);
        int recovered = 0;
        for (Job job : stalled) {
            if (job.canRetry()) {
                job.setStatus(Job.JobStatus.PENDING);
                job.setAttempts(job.getAttempts() + 1);
                job.setHeartbeatAt(Instant.now());
                jobRepository.save(job);
                auditService.record(null, SYSTEM_ROLE, "JOB_RECOVERED", "Job",
                        String.valueOf(job.getId()), AuditEvent.Outcome.FAILURE,
                        java.util.Map.of("reason", "HEARTBEAT_TIMEOUT",
                                "attempts", job.getAttempts()));
                log.warn("Trabajo {} devuelto a PENDING por latido vencido", job.getId());
            } else {
                // Sin intentos restantes: se cierra como fallido y no se reencola.
                job.setStatus(Job.JobStatus.FAILED);
                job.setErrorMessage(truncate("HEARTBEAT_TIMEOUT: el proceso se reinicio "
                        + "sin poder terminar el trabajo."));
                job.setFinishedAt(Instant.now());
                job.setProgressPercent(100);
                jobRepository.save(job);
                auditService.record(null, SYSTEM_ROLE, "JOB_FAILED", "Job",
                        String.valueOf(job.getId()), AuditEvent.Outcome.FAILURE,
                        java.util.Map.of("code", "HEARTBEAT_TIMEOUT"));
                log.warn("Trabajo {} cerrado como FAILED: sin intentos y sin latido", job.getId());
            }
            recovered++;
        }
        return recovered;
    }

    private static String truncate(String value) {
        return value.length() <= 900 ? value : value.substring(0, 900);
    }
}
