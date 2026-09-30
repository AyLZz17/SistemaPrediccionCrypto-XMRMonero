package com.aylzz.xmrforecast.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    /** Intentos fallidos recientes de un correo, base del bloqueo progresivo. */
    @Query("""
            select count(a) from LoginAttempt a
            where a.email = :email and a.successful = false and a.attemptedAt >= :since
            """)
    long countRecentFailures(@Param("email") String email, @Param("since") Instant since);

    Page<LoginAttempt> findAllByOrderByAttemptedAtDesc(Pageable pageable);

    List<LoginAttempt> findTop50ByEmailOrderByAttemptedAtDesc(String email);
}