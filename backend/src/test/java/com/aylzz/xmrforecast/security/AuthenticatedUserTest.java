package com.aylzz.xmrforecast.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pruebas de la identidad autenticada y de la autorizacion por rol. */
class AuthenticatedUserTest {

    @Test
    @DisplayName("Los roles del token permiten autorizar sin volver a la base de datos")
    void carriesRolesForAuthorization() {
        var admin = new AuthenticatedUser(1L, "a@b.com", "jti-1", Set.of(Role.VIEWER, Role.ADMIN));

        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.hasRole(Role.VIEWER)).isTrue();
        assertThat(admin.hasRole(Role.ANALYST)).isFalse();
        assertThat(admin.getName()).isEqualTo("a@b.com");
        assertThat(admin.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Un VIEWER no es admin")
    void viewerIsNotAdmin() {
        var viewer = new AuthenticatedUser(2L, "v@b.com", "jti-2", Set.of(Role.VIEWER));

        assertThat(viewer.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("Sin roles, la identidad no concede nada")
    void emptyRolesGrantsNothing() {
        var user = new AuthenticatedUser(3L, "x@b.com", "jti-3", null);

        assertThat(user.roles()).isEmpty();
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.hasRole(Role.VIEWER)).isFalse();
    }
}