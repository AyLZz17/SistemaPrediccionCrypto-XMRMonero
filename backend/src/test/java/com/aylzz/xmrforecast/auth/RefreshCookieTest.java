package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.user.AuthProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cookie del refresh token y rol efectivo publicado.
 *
 * <p>La cookie es la via recomendada porque un refresh token accesible desde
 * JavaScript es un refresh token que un XSS puede robar. Estos tests fijan los
 * tres atributos que la hacen segura, para que un cambio posterior no los relaje
 * sin que se note.
 */
class RefreshCookieTest {

    @Test
    @DisplayName("la cookie es HttpOnly, Secure y SameSite=Strict")
    void theCookieCarriesTheSecurityAttributes() {
        String header = RefreshCookie.issue("token-opaco", 3600).toString();

        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("SameSite=Strict");
        // El path limitado al endpoint de refresh evita que viaje en cada peticion.
        assertThat(header).contains("Path=/api/v1/auth");
    }

    @Test
    @DisplayName("borrar la cookie la deja sin validez")
    void clearingUsesTheSameAttributesAndZeroMaxAge() {
        String header = RefreshCookie.clear().toString();

        assertThat(header).contains("Max-Age=0");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("SameSite=Strict");
    }

    @Test
    void readsTheCookieWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RefreshCookie.NAME, "desde-cookie"));

        assertThat(RefreshCookie.read(request)).isEqualTo("desde-cookie");
    }

    @Test
    void returnsNullWhenTheCookieIsAbsentOrBlank() {
        assertThat(RefreshCookie.read(new MockHttpServletRequest())).isNull();

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie(RefreshCookie.NAME, ""));
        assertThat(RefreshCookie.read(blank)).isNull();

        assertThat(RefreshCookie.read(null)).isNull();
    }

    @Test
    @DisplayName("el rol efectivo es el mas alto del conjunto")
    void theEffectiveRoleIsTheHighestOne() {
        assertThat(UserResponse.effectiveRole(Set.of(Role.VIEWER))).isEqualTo(Role.VIEWER);
        assertThat(UserResponse.effectiveRole(Set.of(Role.VIEWER, Role.ANALYST)))
                .isEqualTo(Role.ANALYST);
        assertThat(UserResponse.effectiveRole(Set.of(Role.VIEWER, Role.ANALYST, Role.ADMIN)))
                .isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("sin roles se degrada a VIEWER, nunca a nada")
    void withoutRolesItFallsBackToViewer() {
        // Degradar a "sin rol" bloqueaba las rutas y ocultaba el motivo; VIEWER
        // es el minimo privilegio explicito y sigue siendo funcional.
        assertThat(UserResponse.effectiveRole(Set.of())).isEqualTo(Role.VIEWER);
        assertThat(UserResponse.effectiveRole(null)).isEqualTo(Role.VIEWER);
    }

    @Test
    @DisplayName("la vista publica usa ids opacos y nunca expone el hash")
    void thePublicViewPublishesOpaqueIdsAndNoSecrets() {
        var response = UserResponse.of(42L, "ana@example.com", "Ana", Set.of(Role.ANALYST),
                AuthProvider.LOCAL, true, UserResponse.UserStatusView.ACTIVE,
                null, null);

        assertThat(response.id()).isEqualTo("42");
        assertThat(response.role()).isEqualTo(Role.ANALYST);
        assertThat(response.roles()).containsExactlyInAnyOrder(Role.ANALYST);
        // El record no tiene ningun campo de hash ni de token: no hay que filtrarlos.
        assertThat(java.util.Arrays.stream(UserResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("passwordHash", "token", "secret");
    }
}
