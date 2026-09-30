package com.aylzz.xmrforecast.auth.dto;

import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.user.AuthProvider;

import java.time.Instant;
import java.util.Set;

/**
 * Vista publica de un usuario. Nunca incluye hash, token ni intentos de login.
 *
 * <p>Publica dos Representations del rol a proposito:
 * <ul>
 *   <li>{@code role}: el rol efectivo, es decir el mas alto del conjunto. Es lo
 *       que la interfaz necesita para decidir que mostrar, y evita que cada
 *       cliente reimplemente "cual de estos roles manda".</li>
 *   <li>{@code roles}: el conjunto completo, para pantallas de administracion y
 *       auditoria.</li>
 * </ul>
 * Publicar solo el conjunto obligaba al cliente a derivar el rol efectivo, y
 * como el valor por defecto de una derivacion fallida es "sin permisos", un
 * error de derivacion degradaba a un ADMIN hasta las restricciones sin avisar.
 */
public record UserResponse(
        String id,
        String email,
        String fullName,
        Role role,
        Set<Role> roles,
        AuthProvider provider,
        boolean emailVerified,
        UserStatusView status,
        Instant lastLoginAt,
        Instant createdAt
) {
    /** Rol efectivo a partir del conjunto completo. */
    public static Role effectiveRole(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return Role.VIEWER;
        }
        return roles.contains(Role.ADMIN) ? Role.ADMIN
                : roles.contains(Role.ANALYST) ? Role.ANALYST
                : Role.VIEWER;
    }

    public static UserResponse of(Long id, String email, String fullName, Set<Role> roles,
                                 AuthProvider provider, boolean emailVerified, UserStatusView status,
                                 Instant lastLoginAt, Instant createdAt) {
        Set<Role> safeRoles = roles == null ? Set.of() : Set.copyOf(roles);
        return new UserResponse(Ids.of(id), email, fullName, effectiveRole(safeRoles), safeRoles,
                provider, emailVerified, status, lastLoginAt, createdAt);
    }

    public enum UserStatusView {
        PENDING_VERIFICATION,
        ACTIVE,
        SUSPENDED,
        DELETED
    }
}
