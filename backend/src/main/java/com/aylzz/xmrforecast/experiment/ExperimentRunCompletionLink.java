package com.aylzz.xmrforecast.experiment;

import com.aylzz.xmrforecast.job.Job;
import com.aylzz.xmrforecast.job.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Implementacion del puente entre el worker de trabajos y las corridas.
 *
 * <p>Cuando un trabajo termina, la corrida asociada pasa a un estado terminal y
 * el experimento se recalcula a partir de sus corridas. Sin esta pieza, un
 * experimento quedaria en RUNNING para siempre aunque su trabajo hubiera
 * terminado, que es exactamente el tipo de estado mentiroso que R-21 prohibe.
 */
@Component
public class ExperimentRunCompletionLink implements JobWorker.ExperimentRunLink {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunCompletionLink.class);

    private final ExperimentRunRepository runRepository;
    private final ExperimentRepository experimentRepository;

    public ExperimentRunCompletionLink(ExperimentRunRepository runRepository,
                                       ExperimentRepository experimentRepository) {
        this.runRepository = runRepository;
        this.experimentRepository = experimentRepository;
    }

    @Override
    @Transactional
    public void onJobCompleted(Job job) {
        finishRun(job, ExperimentRun.RunStatus.COMPLETED, null);
    }

    /**
     * El trabajo termino sin exito. La corrida pasa a FAILED y el experimento se
     * recalcula, igual que en el camino de exito: si no, un trabajo muerto dejaba
     * el experimento en RUNNING indefinidamente, con la UI anunciando una
     * ejecucion en curso que no existia.
     */
    @Override
    @Transactional
    public void onJobFailed(Job job, String reason) {
        finishRun(job, ExperimentRun.RunStatus.FAILED, reason);
    }

    private void finishRun(Job job, ExperimentRun.RunStatus status, String reason) {
        Long runId = payloadLong(job, "runId");
        if (runId == null) {
            return;
        }
        var optional = runRepository.findById(runId);
        if (optional.isEmpty()) {
            return;
        }
        ExperimentRun run = optional.get();
        if (run.getStatus() == ExperimentRun.RunStatus.COMPLETED
                || run.getStatus() == ExperimentRun.RunStatus.FAILED) {
            // Ya resuelta por otro desenlace del mismo trabajo: no se pisa.
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        run.setStartedAt(run.getStartedAt() == null ? job.getStartedAt() : run.getStartedAt());
        if (reason != null && !reason.isBlank()) {
            run.setErrorMessage(reason.length() > 900 ? reason.substring(0, 900) : reason);
        }
        runRepository.save(run);

        refreshExperimentStatus(run.getExperimentId());
        log.info("Corrida {} en {} tras el trabajo {}", run.getRunKey(), status, job.getJobKey());
    }

    /**
     * Recalcula el estado del experimento a partir de sus corridas: si alguna
     * sigue pendiente o en curso, el experimento no ha terminado.
     */
    private void refreshExperimentStatus(Long experimentId) {
        var experiment = experimentRepository.findById(experimentId).orElse(null);
        if (experiment == null) {
            return;
        }
        var runs = runRepository.findAllByExperimentId(experimentId);
        Experiment.ExperimentStatus next;
        if (runs.isEmpty()) {
            next = Experiment.ExperimentStatus.DRAFT;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == ExperimentRun.RunStatus.PENDING
                || r.getStatus() == ExperimentRun.RunStatus.RUNNING)) {
            next = Experiment.ExperimentStatus.RUNNING;
        } else if (runs.stream().anyMatch(r -> r.getStatus() == ExperimentRun.RunStatus.CANCELLED)
                && runs.stream().noneMatch(r -> r.getStatus() == ExperimentRun.RunStatus.COMPLETED)) {
            next = Experiment.ExperimentStatus.CANCELLED;
        } else if (runs.stream().allMatch(r -> r.getStatus() == ExperimentRun.RunStatus.COMPLETED)) {
            next = Experiment.ExperimentStatus.COMPLETED;
        } else {
            // Una mezcla de completadas, fallidas y canceladas: el experimento se
            // publica como fallido, no como completado. R-09 obliga a reportar un
            // resultado negativo tal cual.
            next = Experiment.ExperimentStatus.FAILED;
        }
        if (next != experiment.getStatus()) {
            experiment.setStatus(next);
            experiment.setUpdatedAt(Instant.now());
            experimentRepository.save(experiment);
        }
    }

    private static Long payloadLong(Job job, String key) {
        Map<String, Object> payload = job.getPayload();
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
