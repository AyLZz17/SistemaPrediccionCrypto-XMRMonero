package com.aylzz.xmrforecast.job;

import com.aylzz.xmrforecast.audit.AuditEventRepository;
import com.aylzz.xmrforecast.audit.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrato transaccional de la cola de trabajos.
 *
 * <p><strong>Por que un test de codigo fuente y no solo de comportamiento.</strong>
 * El fallo que pervive desde T-027 era invisible a los tests de unidad y al guion
 * de verificacion HTTP: {@code JobRepository.claimIfPending} es un
 * {@code @Modifying} sin {@code @Transactional}, y la documentacion oficial de
 * Spring Data JPA es explicita ("Declared query methods ... do not get any
 * transaction configuration applied by default"). Sin transaccion, el
 * {@code flushAutomatically = true} fallaba con
 * {@code InvalidDataAccessApiUsageException: No EntityManager with actual
 * transaction available} en cada barrido y la cola no procesaba nada: 43/43
 * comprobaciones HTTP pasaban mientras el sistema estaba parado.
 *
 * <p>Un test que solo ejecutara {@code claim} con un repositorio simulado
 * pasaria igual, porque el fallo no esta en la logica sino en la anotacion. Por
 * eso se comprueban las dos cosas: la anotacion presente y el comportamiento.
 */
class JobQueueTest {

    @Test
    @DisplayName("Todo @Modifying de un repositorio lleva @Transactional")
    void modifyingRepositoryMethodsAreTransactional() throws Exception {
        assertModifyingMethodsAreTransactional(JobRepository.class);
        assertModifyingMethodsAreTransactional(
                com.aylzz.xmrforecast.user.RevokedTokenRepository.class);
        assertModifyingMethodsAreTransactional(
                com.aylzz.xmrforecast.mlmodel.ModelVersionRepository.class);
    }

    /** Servicio de auditoria que no escribe nada: aqui se prueba la cola. */
    private static AuditService noopAudit() {
        return new AuditService(mock(AuditEventRepository.class), null);
    }

    private static void assertModifyingMethodsAreTransactional(Class<?> repository)
            throws NoSuchMethodException {
        for (Method method : repository.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.data.jpa.repository.Modifying.class) == null) {
                continue;
            }
            assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                    .as("%s#%s es @Modifying y necesita @Transactional: sin ella Spring Data JPA "
                                    + "no abre transaccion y el flush falla en runtime",
                            repository.getSimpleName(), method.getName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("AuditService escribe el evento en su propia transaccion")
    void auditRecordIsRequiresNew() throws Exception {
        // Una llamada interna (this.record(...)) no atraviesa el proxy, de modo que
        // REQUIRES_NEW no se aplicaria y el evento moriria con el rollback de la
        // operacion de negocio. AuditService se inyecta a si mismo con @Lazy para
        // que la llamada salga por el proxy.
        Class<?>[] signature = {Long.class, String.class, String.class, String.class,
                String.class, com.aylzz.xmrforecast.audit.AuditEvent.Outcome.class,
                java.util.Map.class};
        var transactional = AuditService.class.getMethod("record", signature)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(transactional).as("AuditService#record debe ser transaccional").isNotNull();
        assertThat(transactional.propagation())
                .as("AuditService#record debe usar REQUIRES_NEW")
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("Un fallo de auditoria no se propaga a la peticion de negocio")
    void auditFailureNeverBreaksTheCaller() {
        // El catch va FUERA de la transaccion. Dentro, el fallo marcaria la
        // transaccion rollback-only y al confirmarla Spring lanzaria
        // UnexpectedRollbackException, que escaparia al cliente como un 500
        // atribuible a la auditoria. Ese fue el defecto real observado: el alta
        // de usuario devolvia 500 cuando el INSERT de auditoria fallaba.
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "violates foreign key constraint audit_events_actor_user_id_fkey"));

        AuditService service = new AuditService(repository, null);

        assertThatCode(() -> service.success(1L, "VIEWER", "AUTH_REGISTER", "User",
                "1", java.util.Map.of("provider", "LOCAL"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Un evento de auditoria bien formado si llega a la base de datos")
    void auditEventIsPersisted() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        AuditService service = new AuditService(repository, null);

        service.record(7L, "ADMIN", "MODEL_PROMOTED", "ModelVersion", "9",
                com.aylzz.xmrforecast.audit.AuditEvent.Outcome.SUCCESS,
                java.util.Map.of("modelKey", "lstm_base"));

        ArgumentCaptor<com.aylzz.xmrforecast.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.aylzz.xmrforecast.audit.AuditEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().getActorUserId()).isEqualTo(7L);
        assertThat(event.getValue().getAction()).isEqualTo("MODEL_PROMOTED");
        assertThat(event.getValue().getDetails()).containsEntry("modelKey", "lstm_base");
    }

    @Test
    @DisplayName("JobWorker no se invoca a si mismo para abrir transacciones")
    void workerDoesNotSelfInvokeTransactionalMethods() throws Exception {
        // Si poll() llamara a un metodo transaccional suyo mediante `this`, el
        // proxy no se cruzaria. Se comprueba que las operaciones transaccionales
        // viven en JobQueue, otro bean.
        Method poll = JobWorker.class.getMethod("poll");
        assertThat(JobWorker.class.getDeclaredMethods())
                .as("JobWorker no debe declarar metodos @Transactional propios: su "
                        + "invocacion interna saltaria el proxy y la transaccion no se aplicaria")
                .noneMatch(method -> method.getAnnotation(
                        org.springframework.transaction.annotation.Transactional.class) != null);
        assertThat(poll).isNotNull();
    }

    @Test
    @DisplayName("claim delega en JobQueue, que es quien abre la transaccion")
    void claimIsDelegatedToTheQueue() {
        JobRepository repository = mock(JobRepository.class);
        JobQueue queue = new JobQueue(repository, noopAudit());
        when(repository.claimIfPending(eq(7L), eq(Job.JobStatus.PENDING),
                eq(Job.JobStatus.RUNNING))).thenReturn(1);

        assertThat(queue.claim(7L)).isTrue();
        verify(repository).claimIfPending(7L, Job.JobStatus.PENDING, Job.JobStatus.RUNNING);
    }

    @Test
    @DisplayName("Un trabajo sin turno se queda como estaba")
    void unstakedJobIsNotTouched() {
        JobRepository repository = mock(JobRepository.class);
        JobQueue queue = new JobQueue(repository, noopAudit());
        when(repository.findStalled(any(), any())).thenReturn(java.util.List.of());

        assertThat(queue.recoverStalled(Instant.now())).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("El reclamo se declara REQUIRES_NEW para que se confirme de inmediato")
    void claimIsRequiresNew() throws Exception {
        var transactional = JobQueue.class.getMethod("claim", Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("Un trabajo colgado sin intentos se cierra como FAILED, no se reencola")
    void stalledJobWithoutAttemptsIsClosed() {
        JobRepository repository = mock(JobRepository.class);
        JobQueue queue = new JobQueue(repository, noopAudit());
        Job job = new Job();
        job.setId(3L);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setAttempts(3);
        job.setMaxAttempts(3);
        when(repository.findStalled(any(), any())).thenReturn(java.util.List.of(job));

        assertThat(queue.recoverStalled(Instant.now().minusSeconds(900))).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Un trabajo colgado con intentos vuelve a la cola")
    void stalledJobWithAttemptsIsRequeued() {
        JobRepository repository = mock(JobRepository.class);
        JobQueue queue = new JobQueue(repository, noopAudit());
        Job job = new Job();
        job.setId(4L);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setAttempts(1);
        job.setMaxAttempts(3);
        when(repository.findStalled(any(), any())).thenReturn(java.util.List.of(job));

        queue.recoverStalled(Instant.now().minusSeconds(900));

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.PENDING);
        assertThat(job.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Job.isTerminal refleja los tres estados finales")
    void terminalStates() {
        for (Job.JobStatus status : new Job.JobStatus[]{Job.JobStatus.COMPLETED,
                Job.JobStatus.FAILED, Job.JobStatus.CANCELLED}) {
            Job job = new Job();
            job.setStatus(status);
            assertThat(job.isTerminal()).as("%s es terminal", status).isTrue();
        }
        for (Job.JobStatus status : new Job.JobStatus[]{Job.JobStatus.PENDING,
                Job.JobStatus.RUNNING}) {
            Job job = new Job();
            job.setStatus(status);
            assertThat(job.isTerminal()).as("%s no es terminal", status).isFalse();
        }
    }

    }

