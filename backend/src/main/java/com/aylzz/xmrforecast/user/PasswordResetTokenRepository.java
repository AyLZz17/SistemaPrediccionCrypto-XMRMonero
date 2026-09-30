package com.aylzz.xmrforecast.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    java.util.List<PasswordResetToken> findAllByUserIdAndPurposeAndConsumedAtIsNull(
            Long userId, PasswordResetToken.Purpose purpose);
}