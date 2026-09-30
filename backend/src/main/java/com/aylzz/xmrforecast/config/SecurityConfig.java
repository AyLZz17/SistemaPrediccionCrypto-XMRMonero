package com.aylzz.xmrforecast.config;

import com.aylzz.xmrforecast.common.ApiError;
import com.aylzz.xmrforecast.common.CorrelationIdFilter;
import com.aylzz.xmrforecast.common.RequestContext;
import com.aylzz.xmrforecast.security.JwtAuthenticationFilter;
import com.aylzz.xmrforecast.security.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configuracion de seguridad (OWASP ASVS / API Security Top 10).
 *
 * <p>Decisiones:
 * <ul>
 *   <li>Sesion sin estado: JWT propio, sin cookies de sesion ni JSESSIONID.</li>
 *   <li>CSRF desactivado porque no hay autenticacion por cookie: el token viaja en
 *       la cabecera Authorization, que un navegador no envia automaticamente.</li>
 *   <li>CORS limitado a origenes HTTPS explicitos.</li>
 *   <li>CSRF de OAuth se resuelve con state + nonce en el flujo de Google.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final AppProperties properties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(AppProperties properties,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.objectMapper = objectMapper;
    }

    /** BCrypt con coste 12: equilibrio razonable entre coste y latencia. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // HSTS: el navegador solo hablara HTTPS con este origen (R-33).
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(properties.security().hstsMaxAgeSeconds()))
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(auth -> auth
                        // Documentacion de la API: solo para ADMIN.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .hasRole("ADMIN")
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Publico: registro, login, OAuth de Google y recuperacion.
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/google/**",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset",
                                "/api/v1/auth/verify-email",
                                "/api/v1/meta/disclaimer"
                        ).permitAll()

                        // Autenticado: el resto de la API.
                        .requestMatchers("/api/v1/**").authenticated()

                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED",
                                        "Se requieren credenciales validas para acceder a este recurso."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "No tiene permisos para acceder a este recurso.")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // El rate limit va antes de JWT para que un intento de fuerza bruta
                // se descarte antes de llegar a verificar la contrasena.
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                            String code, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var body = ApiError.of(status.value(), status.getReasonPhrase(), code, message,
                "/api/v1", RequestContext.requestId());
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * CORS limitado a origenes HTTPS configurados. Sin comodines: un
     * {@code allowCredentials} con {@code *} es incompatible con la especificacion
     * y ademas abriria la API a cualquier sitio.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.security().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Request-Id", "X-Trace-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-Trace-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/**", configuration);
        return source;
    }
}