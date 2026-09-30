package com.aylzz.xmrforecast.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * Busca una notificacion restringida a su propietario. La comprobacion de
     * propiedad va en la consulta para evitar IDOR (OWASP API1).
     */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    List<Notification> findTop10ByUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long userId);
}