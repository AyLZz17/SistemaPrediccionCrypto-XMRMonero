package com.aylzz.xmrforecast.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio para solicitudes de acceso a rol ANALYST.
 */
@Repository
public interface AnalystAccessRequestRepository extends JpaRepository<AnalystAccessRequest, Long> {

    /**
     * Busca la solicitud del usuario (si existe).
     */
    Optional<AnalystAccessRequest> findByUserId(Long userId);

    /**
     * Busca solicitudes por estado con paginación.
     */
    Page<AnalystAccessRequest> findByStatus(AnalystAccessRequestStatus status, Pageable pageable);

    /**
     * Verifica si el usuario ya tiene una solicitud PENDING.
     * Usa el índice único parcial para ser eficiente.
     */
    @Query("SELECT COUNT(r) > 0 FROM AnalystAccessRequest r WHERE r.user.id = :userId AND r.status = 'PENDING'")
    boolean existsPendingByUserId(@Param("userId") Long userId);

    /**
     * Verifica si el usuario tiene una solicitud en cualquier estado terminal
     * (APPROVED, REJECTED, REVOKED) que impida crear una nueva según política.
     * La política actual permite nueva solicitud si la anterior fue REJECTED.
     */
    @Query("SELECT COUNT(r) > 0 FROM AnalystAccessRequest r WHERE r.user.id = :userId AND r.status IN ('PENDING', 'APPROVED', 'REVOKED')")
    boolean existsBlockingByUserId(@Param("userId") Long userId);
}