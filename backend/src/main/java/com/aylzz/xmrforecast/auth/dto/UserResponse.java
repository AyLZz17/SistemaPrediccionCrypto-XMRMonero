package com.aylzz.xmrforecast.auth.dto;

import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.user.AuthProvider;

import java.time.Instant;
import java.util.Set;

/** Vista publica de un usuario. Nunca incluye hash, token ni intentos de login. */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        Set<Role> roles,
        AuthProvider provider,
        boolean emailVerified,
        UserStatusView status,
        Instant lastLoginAt,
        Instant createdAt
) {
    public enum UserStatusView {
        PENDING_VERIFICATION,
        ACTIVE,
        SUSPENDED,
        DELETED
    }
}