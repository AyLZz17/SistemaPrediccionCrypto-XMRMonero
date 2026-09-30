package com.aylzz.xmrforecast.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cola de trabajos.
 *
 * <p>Las consultas de estado usan un <strong>parametro ligado</strong> en lugar de
 * la constante {@code com.aylzz.xmrforecast.job.Job.JobStatus.PENDING} escrita en
 * el texto JPQL. La forma calificada funciona en un SELECT en algunas versiones
 * de Hibernate, pero en un UPDATE el analizador la rechaza con
 * {@code Could not interpret path expression} y la aplicacion no arranca. Un
 * parametro es portable entre versiones y ademas es immune a los cambios de
 * paquete.
 */
public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    Optional<Job> findByJobKey(String jobKey);

    Page<Job> findAllByOrderByQueuedAtDesc(Pageable pageable);

    Page<Job> findAllByCreatedByOrderByQueuedAtDesc(Long createdBy, Pageable pageable);

    Page<Job> findAllByStatusOrderByQueuedAtAsc(Job.JobStatus status, Pageable pageable);

    List<Job> findAllByStatus(Job.JobStatus status);

    long countByStatus(Job.JobStatus status);

    /** Trabajos PENDING listos para tomar (cola de entrada). */
    @Query("select j from Job j where j.status = :status order by j.queuedAt asc")
    List<Job> findPending(@Param("status") Job.JobStatus status);

    /**
     * Trabajos RUNNING cuyo latido es antiguo: quedaron colgados por un reinicio
     * del worker y hay que devolverlos a PENDING o marcar FAILED.
     */
    @Query("select j from Job j where j.status = :status "
            + "and (j.heartbeatAt is null or j.heartbeatAt < :cutoff)")
    List<Job> findStalled(@Param("status") Job.JobStatus status, @Param("cutoff") Instant cutoff);

    /**
     * Reclamo optimista de un trabajo.
     *
     * <p>Es la garantia de que dos instancias del backend no ejecuten el mismo
     * trabajo: la condicion {@code status = PENDING} hace que la segunda
     * actualizacion afecte a cero filas. Devuelve el numero de filas afectadas y
     * el worker solo sigue adelante si es 1.
     *
     * <p><strong>{@code @Transactional} es obligatorio aqui.</strong> La
     * documentacion oficial de Spring Data JPA dice que los metodos de consulta
     * declarados <em>no reciben configuracion transaccional por defecto</em>
     * ("Declared query methods (including default methods) do not get any
     * transaction configuration applied by default"), y un {@code @Modifying} con
     * {@code flushAutomatically = true} necesita una transaccion real para hacer
     * el flush: sin ella, cada barrido del worker terminaba con
     * {@code InvalidDataAccessApiUsageException: No EntityManager with actual
     * transaction available for current thread - cannot reliably process 'flush'
     * call} y la cola no se procesaba nunca.
     *
     * <p>Referencia: Spring Data JPA, "Transactionality" ->
     * "Transactional query methods".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = :running, "
            + "j.startedAt = CURRENT_TIMESTAMP, j.heartbeatAt = CURRENT_TIMESTAMP, "
            + "j.attempts = j.attempts + 1 "
            + "where j.id = :id and j.status = :pending")
    int claimIfPending(@Param("id") Long id,
                       @Param("pending") Job.JobStatus pending,
                       @Param("running") Job.JobStatus running);
}
