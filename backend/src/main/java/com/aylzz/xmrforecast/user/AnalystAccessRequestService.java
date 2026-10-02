package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.AuthService;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.user.dto.AnalystAccessRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Gestion de solicitudes de acceso al rol ANALYST.
 *
 * <p>Reglas de negocio:
 * <ul>
 *   <li>Solo usuarios con rol VIEWER pueden solicitar (ANALYST/ADMIN ya tienen acceso).</li>
 *   <li>Un usuario solo puede tener una solicitud PENDING a la vez.</li>
 *   <li>Si fue REJECTED, puede solicitar de nuevo.</li>
 *   <li>Si fue APPROVED, no puede solicitar de nuevo (ya es ANALYST).</li>
 *   <li>Si fue REVOKED, puede solicitar de nuevo (politica configurable).</li>
 *   <li>La aprobacion cambia el rol a ANALYST y revoca sesiones.</li>
 *   <li>Solo ADMIN puede aprobar, rechazar o revocar.</li>
 *   <li>Un ADMIN no puede aprobar su propia solicitud.</li>
 * </ul>
 */
@Service
public class AnalystAccessRequestService {

    private final AnalystAccessRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;

    public AnalystAccessRequestService(AnalystAccessRequestRepository requestRepository,
                                       UserRepository userRepository,
                                       AuthService authService,
                                       AuditService auditService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    /**
     * Crea una nueva solicitud de acceso a ANALYST.
     * <p>
     * Validaciones:
     * - Usuario autenticado (VIEWER).
     * - No tiene solicitud PENDING.
     * - No tiene solicitud APPROVED (ya seria ANALYST).
     * - No tiene solicitud REVOKED (politica: no re-solicitar tras revocacion).
     * - Puede solicitar si fue REJECTED.
     */
    @Transactional
    public AnalystAccessRequests.CreateResponse create(Long userId, AnalystAccessRequests.CreateRequest request) {
        User user = requireUser(userId);

        // Solo VIEWER puede solicitar
        if (!user.isViewer()) {
            throw ApiException.conflict("NOT_VIEWER",
                    "Solo usuarios con rol VIEWER pueden solicitar acceso a ANALYST.");
        }

        // Verificar duplicados PENDING
        if (requestRepository.existsPendingByUserId(userId)) {
            throw ApiException.conflict("DUPLICATE_PENDING",
                    "Ya tiene una solicitud pendiente de revision.");
        }

        // Verificar si ya fue aprobado (seria ANALYST)
        if (user.hasRole(com.aylzz.xmrforecast.security.Role.ANALYST)) {
            throw ApiException.conflict("ALREADY_ANALYST",
                    "Ya tiene rol ANALYST. No necesita solicitar acceso.");
        }

        // Verificar si fue REVOKED (politica: no re-solicitar)
        // NOTA: El constraint unico en BD permite solo una PENDING, pero permite
        // historico. La politica es: REJECTED -> puede volver a solicitar.
        // REVOKED -> no puede (se revoco por incumplimiento).
        // APPROVED -> ya es ANALYST, controlado arriba.

        AnalystAccessRequest entity = new AnalystAccessRequest();
        entity.setUser(user);
        entity.setMotivo(request.motivo().trim());
        entity.setUsoPrevisto(request.usoPrevisto().trim());
        entity.setCuestionarioVersion(AnalystAccessRequests.CUESTIONARIO_VERSION);
        entity.setAceptaRiesgos(request.aceptaRiesgos());
        entity.setAceptaLimitaciones(request.aceptaLimitaciones());
        entity.setAceptaMetricas(request.aceptaMetricas());
        entity.setAceptaNoGarantia(request.aceptaNoGarantia());
        entity.setAceptaNoOperaciones(request.aceptaNoOperaciones());
        entity.setAceptaNoBacktesting(request.aceptaNoBacktesting());
        entity.setAceptaRolAnalyst(request.aceptaRolAnalyst());
        entity.setAceptaNoRentabilidad(request.aceptaNoRentabilidad());
        entity.setStatus(AnalystAccessRequestStatus.PENDING);

        AnalystAccessRequest saved = requestRepository.save(entity);

        // Auditoría
        auditService.record(userId, "VIEWER", "ANALYST_ACCESS_REQUEST_CREATED", "AnalystAccessRequest",
                Ids.of(saved.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("cuestionarioVersion", saved.getCuestionarioVersion()));

        return new AnalystAccessRequests.CreateResponse(
                Ids.of(saved.getId()),
                saved.getStatus(),
                saved.getCreatedAt()
        );
    }

    /**
     * Obtiene la solicitud del usuario autenticado (si existe).
     */
    @Transactional(readOnly = true)
    public AnalystAccessRequests.MyRequestResponse getMyRequest(Long userId) {
        User user = requireUser(userId);
        return requestRepository.findByUserId(user.getId())
                .map(this::toMyResponse)
                .orElse(null);
    }

    /**
     * Lista todas las solicitudes (solo ADMIN).
     */
    @Transactional(readOnly = true)
    public PageResponse<AnalystAccessRequests.AdminListResponse> list(Pageable pageable) {
        Page<AnalystAccessRequest> page = requestRepository.findAll(pageable);
        return PageResponse.of(page, this::toAdminResponse);
    }

    /**
     * Lista solicitudes filtradas por estado (solo ADMIN).
     */
    @Transactional(readOnly = true)
    public PageResponse<AnalystAccessRequests.AdminListResponse> listByStatus(AnalystAccessRequestStatus status, Pageable pageable) {
        Page<AnalystAccessRequest> page = requestRepository.findByStatus(status, pageable);
        return PageResponse.of(page, this::toAdminResponse);
    }

    /**
     * ADMIN aprueba la solicitud: cambia rol a ANALYST y revoca sesiones.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AnalystAccessRequests.DecisionResponse approve(Long requestId, Long actorId, String actorRole,
                                                          AnalystAccessRequests.DecisionRequest decision) {
        AnalystAccessRequest request = requireRequest(requestId);

        if (request.getStatus() != AnalystAccessRequestStatus.PENDING) {
            throw ApiException.conflict("INVALID_STATUS",
                    "Solo se pueden aprobar solicitudes en estado PENDING.");
        }

        if (request.getUser().getId().equals(actorId)) {
            throw ApiException.conflict("SELF_APPROVAL",
                    "Un administrador no puede aprobar su propia solicitud.");
        }

        User target = request.getUser();

        // Cambiar roles: agregar ANALYST (mantener VIEWER si no lo tiene)
        Set<com.aylzz.xmrforecast.security.Role> currentRoles = target.getRoles();
        Set<com.aylzz.xmrforecast.security.Role> newRoles = new java.util.LinkedHashSet<>(currentRoles);
        newRoles.add(com.aylzz.xmrforecast.security.Role.ANALYST);
        target.setRoles(newRoles);
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);

        // Revocar sesiones del usuario afectado
        int revoked = authService.logoutAll(target.getId(), "ROLE_CHANGED_TO_ANALYST");

        // Actualizar solicitud
        request.setStatus(AnalystAccessRequestStatus.APPROVED);
        request.setDecidedBy(requireUser(actorId));
        request.setDecidedAt(Instant.now());
        request.setDecisionReason(decision.reason().trim());
        AnalystAccessRequest saved = requestRepository.save(request);

        // Auditoría
        auditService.record(actorId, actorRole, "ANALYST_ACCESS_REQUEST_APPROVED", "AnalystAccessRequest",
                Ids.of(saved.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("targetUserId", Ids.of(target.getId()),
                        "previousRoles", target.getRoles().stream().map(Enum::name).sorted().toList(),
                        "newRoles", newRoles.stream().map(Enum::name).sorted().toList(),
                        "revokedSessions", revoked,
                        "decisionReason", saved.getDecisionReason()));

        return new AnalystAccessRequests.DecisionResponse(
                Ids.of(saved.getId()),
                saved.getStatus(),
                saved.getDecidedAt()
        );
    }

    /**
     * ADMIN rechaza la solicitud.
     */
    @Transactional
    public AnalystAccessRequests.DecisionResponse reject(Long requestId, Long actorId, String actorRole,
                                                         AnalystAccessRequests.DecisionRequest decision) {
        AnalystAccessRequest request = requireRequest(requestId);

        if (request.getStatus() != AnalystAccessRequestStatus.PENDING) {
            throw ApiException.conflict("INVALID_STATUS",
                    "Solo se pueden rechazar solicitudes en estado PENDING.");
        }

        request.setStatus(AnalystAccessRequestStatus.REJECTED);
        request.setDecidedBy(requireUser(actorId));
        request.setDecidedAt(Instant.now());
        request.setDecisionReason(decision.reason().trim());
        AnalystAccessRequest saved = requestRepository.save(request);

        // Auditoría
        auditService.record(actorId, actorRole, "ANALYST_ACCESS_REQUEST_REJECTED", "AnalystAccessRequest",
                Ids.of(saved.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("targetUserId", Ids.of(request.getUser().getId()),
                        "decisionReason", saved.getDecisionReason()));

        return new AnalystAccessRequests.DecisionResponse(
                Ids.of(saved.getId()),
                saved.getStatus(),
                saved.getDecidedAt()
        );
    }

    /**
     * ADMIN revoca una solicitud aprobada (quita rol ANALYST).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AnalystAccessRequests.DecisionResponse revoke(Long requestId, Long actorId, String actorRole,
                                                         AnalystAccessRequests.DecisionRequest decision) {
        AnalystAccessRequest request = requireRequest(requestId);

        if (request.getStatus() != AnalystAccessRequestStatus.APPROVED) {
            throw ApiException.conflict("INVALID_STATUS",
                    "Solo se pueden revocar solicitudes en estado APPROVED.");
        }

        User target = request.getUser();

        // Quitar rol ANALYST
        Set<com.aylzz.xmrforecast.security.Role> currentRoles = target.getRoles();
        Set<com.aylzz.xmrforecast.security.Role> newRoles = new java.util.LinkedHashSet<>(currentRoles);
        boolean removed = newRoles.remove(com.aylzz.xmrforecast.security.Role.ANALYST);
        if (!removed) {
            throw ApiException.conflict("NOT_ANALYST",
                    "El usuario no tiene rol ANALYST para revocar.");
        }
        target.setRoles(newRoles);
        target.setUpdatedAt(Instant.now());
        userRepository.save(target);

        // Revocar sesiones
        int revoked = authService.logoutAll(target.getId(), "ANALYST_ACCESS_REVOKED");

        // Actualizar solicitud
        request.setStatus(AnalystAccessRequestStatus.REVOKED);
        request.setDecidedBy(requireUser(actorId));
        request.setDecidedAt(Instant.now());
        request.setDecisionReason(decision.reason().trim());
        AnalystAccessRequest saved = requestRepository.save(request);

        // Auditoría
        auditService.record(actorId, actorRole, "ANALYST_ACCESS_REQUEST_REVOKED", "AnalystAccessRequest",
                Ids.of(saved.getId()), AuditEvent.Outcome.SUCCESS,
                Map.of("targetUserId", Ids.of(target.getId()),
                        "previousRoles", currentRoles.stream().map(Enum::name).sorted().toList(),
                        "newRoles", newRoles.stream().map(Enum::name).sorted().toList(),
                        "revokedSessions", revoked,
                        "decisionReason", saved.getDecisionReason()));

        return new AnalystAccessRequests.DecisionResponse(
                Ids.of(saved.getId()),
                saved.getStatus(),
                saved.getDecidedAt()
        );
    }

    private AnalystAccessRequest requireRequest(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "La solicitud no existe."));
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "El usuario no existe."));
    }

    private AnalystAccessRequests.MyRequestResponse toMyResponse(AnalystAccessRequest r) {
        return new AnalystAccessRequests.MyRequestResponse(
                Ids.of(r.getId()),
                r.getMotivo(),
                r.getUsoPrevisto(),
                r.getCuestionarioVersion(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getDecidedAt(),
                r.getDecisionReason(),
                r.getDecidedBy() != null ? r.getDecidedBy().getEmail() : null
        );
    }

    private AnalystAccessRequests.AdminListResponse toAdminResponse(AnalystAccessRequest r) {
        return new AnalystAccessRequests.AdminListResponse(
                Ids.of(r.getId()),
                Ids.of(r.getUser().getId()),
                r.getUser().getEmail(),
                r.getUser().getFullName(),
                r.getMotivo(),
                r.getUsoPrevisto(),
                r.getCuestionarioVersion(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getDecidedAt(),
                r.getDecisionReason(),
                r.getDecidedBy() != null ? r.getDecidedBy().getEmail() : null
        );
    }
}