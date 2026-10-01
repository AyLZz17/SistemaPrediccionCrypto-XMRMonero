package com.aylzz.xmrforecast.security;

import com.aylzz.xmrforecast.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cubos de rate limit: credenciales, superficie publica y API autenticada.
 *
 * <p>El cubo propio de {@code /api/v1/public/**} es el que este filtro gana al
 * abrir la primera pantalla a anonimos: sin el, un scraper vaciaria la serie
 * con el limite alto de la API y dejaria a los usuarios con sesion sin servicio
 * (o, con el limite bajo de login, ni siquiera conseguirian iniciar sesion).
 */
class RateLimitFilterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private static AppProperties properties(boolean enabled) {
        return new AppProperties(
                new AppProperties.Jwt("s".repeat(64), "issuer", "audience", 900, 2_592_000, 60),
                new AppProperties.OAuth2(new AppProperties.OAuth2.Google(
                        null, null, null, null, List.of(), null)),
                new AppProperties.Ml("http://ml-service", 1000, 1000, 1, null),
                new AppProperties.Security(true, 31_536_000L,
                        List.of("https://localhost:3000"),
                        new AppProperties.Security.RateLimit(enabled, 5, 300, 120),
                        new AppProperties.Security.Account(5, 15)),
                new AppProperties.Jobs(3, 900));
    }

    private RateLimitFilter filter(boolean enabled) {
        if (enabled) {
            when(redis.opsForValue()).thenReturn(ops);
        }
        // Mismo ObjectMapper que el de produccion: sin el modulo jsr310 el 422
        // (y aqui el 429) no se puede serializar, porque ApiError lleva Instant.
        return new RateLimitFilter(redis, properties(enabled),
                new ObjectMapper().findAndRegisterModules());
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static FilterChain chainThat(AtomicBoolean invoked) {
        return (req, res) -> invoked.set(true);
    }

    private String bucket() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ops).increment(key.capture());
        return key.getValue();
    }

    @Test
    @DisplayName("la superficie publica tiene cubo propio y limite intermedio")
    void laSuperficiePublicaTieneCuboPropio() throws Exception {
        when(ops.increment(anyString())).thenReturn(121L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter(true).doFilterInternal(request("/api/v1/public/summary"), response,
                chainThat(invoked));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(invoked).isFalse();
        assertThat(bucket()).startsWith("ratelimit:public:");
    }

    @Test
    @DisplayName("dentro del limite anonimo la peticion pasa")
    void dentroDelLimiteAnonimoPasa() throws Exception {
        when(ops.increment(anyString())).thenReturn(120L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter(true).doFilterInternal(request("/api/v1/public/summary"), response,
                chainThat(invoked));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("las rutas de credenciales siguen usando el cubo mas bajo")
    void lasCredencialesUsanSuCubo() throws Exception {
        when(ops.increment(anyString())).thenReturn(6L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter(true).doFilterInternal(request("/api/v1/auth/login"), response,
                chainThat(invoked));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(invoked).isFalse();
        assertThat(bucket()).startsWith("ratelimit:auth:");
    }

    @Test
    @DisplayName("la API autenticada usa su propio cubo y su limite alto")
    void laApiAutenticadaUsaSuCubo() throws Exception {
        when(ops.increment(anyString())).thenReturn(301L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilterInternal(request("/api/v1/market/latest"), response,
                chainThat(new AtomicBoolean(false)));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(bucket()).startsWith("ratelimit:api:");
    }

    @Test
    @DisplayName("con Redis caido la peticion se deja pasar: un fallo de cache no bloquea la API")
    void redisCaidoNoBloqueaLaApi() throws Exception {
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis caido"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter(true).doFilterInternal(request("/api/v1/public/status"), response,
                chainThat(invoked));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("deshabilitado no toca Redis en absoluto")
    void deshabilitadoNoTocaRedis() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter(false).doFilterInternal(request("/api/v1/public/summary"),
                new MockHttpServletResponse(), chainThat(invoked));

        assertThat(invoked).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("la clave de Redis lleva la IP hasheada, nunca en claro")
    void laClaveNoGuardaLaIpEnClaro() throws Exception {
        when(ops.increment(anyString())).thenReturn(1L);

        filter(true).doFilterInternal(request("/api/v1/public/summary"),
                new MockHttpServletResponse(), chainThat(new AtomicBoolean(false)));

        // ratelimit:public: + 64 hexadecimales de SHA-256.
        assertThat(bucket()).matches("ratelimit:public:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("al primer incremento se fija la ventana de un minuto")
    void alPrimerIncrementoSeFijaLaVentana() throws Exception {
        when(ops.increment(anyString())).thenReturn(1L);

        filter(true).doFilterInternal(request("/api/v1/public/summary"),
                new MockHttpServletResponse(), chainThat(new AtomicBoolean(false)));

        verify(redis).expire(anyString(), org.mockito.ArgumentMatchers.eq(
                java.time.Duration.ofMinutes(1)));
    }
}
