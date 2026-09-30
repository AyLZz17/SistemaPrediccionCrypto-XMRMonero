package com.aylzz.xmrforecast.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    boolean existsByJti(String jti);

    /**
     * Guarda la revocacion ignorando duplicados: dos peticiones concurrentes con
     * el mismo jti no deben provocar una violacion de la restriccion unica.
     *
     * <p>{@code @Transactional} es obligatorio: los metodos de consulta declarados
     * de Spring Data JPA no reciben configuracion transaccional por defecto, y un
     * {@code @Modifying} sin transaccion falla. Documentacion oficial de Spring
     * Data JPA, "Transactional query methods".
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO revoked_tokens (jti, user_id, expires_at, revoked_at, reason)
            VALUES (:jti, :userId, :expiresAt, NOW(), :reason)
            ON CONFLICT (jti) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("jti") String jti,
                       @Param("userId") Long userId,
                       @Param("expiresAt") Instant expiresAt,
                       @Param("reason") String reason);

    /** Purga de revocaciones cuyo token ya habria expirado. */
    @Modifying
    @Transactional
    @Query("delete from RevokedToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}