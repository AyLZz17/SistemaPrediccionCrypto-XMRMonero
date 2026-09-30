package com.aylzz.xmrforecast.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    Optional<Job> findByJobKey(String jobKey);

    Page<Job> findAllByOrderByQueuedAtDesc(Pageable pageable);

    Page<Job> findAllByCreatedByOrderByQueuedAtDesc(Long createdBy, Pageable pageable);

    Page<Job> findAllByStatusOrderByQueuedAtAsc(Job.JobStatus status, Pageable pageable);

    List<Job> findAllByStatus(Job.JobStatus status);

    long countByStatus(Job.JobStatus status);

    /** Trabajos PENDING listos para tomar (cola de entrada). */
    @Query("select j from Job j where j.status = com.aylzz.xmrforecast.job.Job.JobStatus.PENDING "
            + "order by j.queuedAt asc")
    List<Job> findPending();

    /**
     * Trabajos RUNNING cuyo latido es antiguo: quedaron colgados por un reinicio
     * del worker y hay que devolverlos a PENDING o marcar FAILED.
     */
    @Query("select j from Job j where j.status = com.aylzz.xmrforecast.job.Job.JobStatus.RUNNING "
            + "and (j.heartbeatAt is null or j.heartbeatAt < :cutoff)")
    List<Job> findStalled(@Param("cutoff") Instant cutoff);
}