package com.aylzz.xmrforecast.security;

import java.security.Principal;
import java.util.Set;

/**
 * Identidad autenticada extraida del access token.
 *
 * <p>Incluye los roles del token para poder autorizar por recurso sin volver a
 * consultar la base de datos en cada peticion.
 */
public record AuthenticatedUser(Long id, String email, String jti, Set<Role> roles) implements Principal {

    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    @Override
    public String getName() {
        return email;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /**
     * Rol efectivo: el mas alto del conjunto. Se usa al registrar auditoria, que
     * guarda un unico {@code actor_role}, y en respuestas que necesitan un rol
     * concreto en lugar de una coleccion.
     */
    public Role role() {
        if (roles.contains(Role.ADMIN)) {
            return Role.ADMIN;
        }
        return roles.contains(Role.ANALYST) ? Role.ANALYST : Role.VIEWER;
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    public boolean hasAtLeast(Role minimum) {
        return roles.stream().anyMatch(r -> r.ordinal() >= minimum.ordinal());
    }
}