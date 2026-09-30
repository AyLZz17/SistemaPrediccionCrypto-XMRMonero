package com.aylzz.xmrforecast.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByJti(String jti);

    /** Tokens activos del usuario, para revocar todas sus sesiones. */
    java.util.List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(Long userId);

    /** Tokens expirados o revocados, candidatos a purga segun la retencion. */
    java.util.List<RefreshToken> findAllByExpiresAtBefore(Instant cutoff);
}