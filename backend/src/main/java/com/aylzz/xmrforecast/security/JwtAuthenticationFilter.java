package com.aylzz.xmrforecast.security;

import com.aylzz.xmrforecast.user.RevokedTokenRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Autentica la peticion a partir del access token Bearer.
 *
 * <p>El token no se registra en ningun log (seccion 12). Si el {@code jti} aparece
 * en la tabla de revocados, la peticion se rechaza aunque el token sea valido.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   RevokedTokenRepository revokedTokenRepository) {
        this.jwtService = jwtService;
        this.revokedTokenRepository = revokedTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            authenticate(token, request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        Claims claims = jwtService.parse(token);
        if (claims == null) {
            return;
        }

        String jti = claims.getId();
        if (jti == null) {
            return;
        }
        // Revocacion explicita por jti (logout, cambio de contrasena, revocacion admin).
        if (revokedTokenRepository.existsByJti(jti)) {
            log.debug("JWT revocado rechazado (jti={})", jti);
            return;
        }

        Object rolesClaim = claims.get(Role.CLAIM);
        if (!(rolesClaim instanceof List<?> roles) || roles.isEmpty()) {
            // Sin roles no hay nada que autorizar: se trata como peticion anonima.
            return;
        }

        var resolvedRoles = new LinkedHashSet<Role>();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (Object role : roles) {
            try {
                Role resolved = Role.fromString(String.valueOf(role));
                resolvedRoles.add(resolved);
                authorities.add(new SimpleGrantedAuthority("ROLE_" + resolved.name()));
            } catch (IllegalArgumentException ex) {
                // Rol desconocido en el token: se ignora en vez de romper la peticion.
                log.warn("Rol desconocido en el token: {}", String.valueOf(role));
            }
        }
        if (authorities.isEmpty()) {
            return;
        }

        var principal = new AuthenticatedUser(Long.valueOf(claims.getSubject()),
                String.valueOf(claims.get("email")), jti, resolvedRoles);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}