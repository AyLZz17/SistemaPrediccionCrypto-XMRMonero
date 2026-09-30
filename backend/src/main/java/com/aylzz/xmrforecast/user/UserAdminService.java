package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.audit.AuditEvent;
import com.aylzz.xmrforecast.audit.AuditService;
import com.aylzz.xmrforecast.auth.AuthService;
import com.aylzz.xmrforecast.auth.dto.UserResponse;
import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Gestion de cuentas y roles. Solo ADMIN (OWASP A01: minimo privilegio).
 *
 * <p>Dos decisiones de seguridad que conviene tener presentes al leer el codigo:
 * <ul>
 *   <li>Un ADMIN <strong>no puede quitarse su propio rol ADMIN</strong> ni degradar
 *       al ultimo ADMIN del sistema. Sin esa proteccion, un unico descuido deja la
 *       instalacion sin nadie que pueda administrarla, y la unica salida seria
 *       tocar la base de datos a mano.</li>
 *   <li>Cambiar un rol <strong>revoca las sesiones activas</strong> del usuario
 *       afectado. Si no, seguiria operando con el token anterior hasta que
 *       expirase, y un rol degradado seguiria siendo efectiva durante 15 minutos.</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private static final int MAX_PAGE_SIZE = 200;

    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;

    public UserAdminService(UserRepository userRepository,
                            AuthService authService,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(int page, int size) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        Page<User> result = userRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.of(result, AdminUserResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(Long id) {
        return AdminUserResponse.from(require(id));
    }

    /**
     * Sustituye el conjunto de roles de una cuenta.
     *
     * <p>Es un reemplazo, no una adicion: asi el rol retirado desaparece de verdad
     * en lugar de quedar cumulado para siempre.
     */
    /**
     * Sustituye el conjunto de roles de una cuenta.
     *
     * <p><strong>SERIALIZABLE, y no por estetica.</strong> La proteccion del ultimo
     * administrador es una lectura y luego una escritura: con el aislamiento por
     * defecto (READ COMMITTED), dosrifugas concurrentes que degradan a dos
     * administradores distintos observan ambas {@code admins == 2} y las dos
     * pasan el control, dejando el sistema sin nadie que pueda restaurarlo.
     * SERIALIZABLE hace que PostgreSQL detecte el conflicto de serializacion y
     * rechace una de las dos, que es la respuesta correcta.
     *
     * <p>Es un reemplazo, no una adicion: asi el rol retirado desaparece de verdad
     * en lugar de quedar cumulado para siempre.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AdminUserResponse changeRoles(Long targetId, Set<Role> requested,
                                         Long actorId, String actorRole) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("EMPTY_ROLE_SET",
                    "Una cuenta debe conservar al menos el rol VIEWER.");
        }

        User target = require(targetId);
        Set<Role> current = target.getRoles() == null ? Set.of() : Set.copyOf(target.getRoles());
        Set<Role> next = new LinkedHashSet<>(requested);

        boolean wasAdmin = current.contains(Role.ADMIN);
        boolean willBeAdmin = next.contains(Role.ADMIN);
        if (wasAdmin && !willBeAdmin) {
            assertNotLastAdmin("No se puede quitar el rol ADMIN al ultimo administrador del sistema.");
            if (targetId.equals(actorId)) {
                throw ApiException.conflict("CANNOT_SELF_DEMOTE",
                        "Un administrador no puede quitarse su propio rol ADMIN.");
            }
        }

        target.setRoles(next);
        target.setUpdatedAt(Instant.now());
        User saved = userRepository.save(target);

        // El token del usuario afectado lleva los roles dentro: sin revocar la
        // sesion, el cambio no seria efectivo hasta que expirase el token.
        int revoked = 0;
        if (!current.equals(next)) {
            revoked = authService.logoutAll(saved.getId(), "ROLE_CHANGED");
        }

        auditService.record(actorId, actorRole, "USER_ROLES_CHANGED", "User",
                Ids.of(targetId), AuditEvent.Outcome.SUCCESS,
                Map.of("previousRoles", current.stream().map(Enum::name).sorted().toList(),
                        "newRoles", next.stream().map(Enum::name).sorted().toList(),
                        "revokedSessions", revoked));

        return AdminUserResponse.from(saved);
    }

    /**
     * Impide quedarse sin ningun administrador capaz de restaurar el sistema.
     *
     * <p>El recuento se hace dentro de la transaccion SERIALIZABLE de
     * {@link #changeRoles}, que es lo que hace que la comprobacion y la escritura
     * sean una sola operacion atomica frente a otras degradaciones concurrentes.
     */
    private void assertNotLastAdmin(String message) {
        long admins = userRepository.countByRoleAndStatusNot(Role.ADMIN, UserStatus.DELETED);
        if (admins <= 1) {
            throw ApiException.conflict("LAST_ADMIN", message);
        }
    }

    private User require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "El usuario no existe."));
    }

    /**
     * Vista de administracion. Reutiliza la vista publica y anade solo el estado
     * operativo; nunca el hash de la contrasena ni ningun token.
     */
    public record AdminUserResponse(
            String id,
            String email,
            String fullName,
            Role role,
            Set<Role> roles,
            String status,
            boolean enabled,
            boolean emailVerified,
            String provider,
            Instant lastLoginAt,
            Instant createdAt
    ) {
        static AdminUserResponse from(User user) {
            UserResponse base = UserResponse.of(user.getId(), user.getEmail(), user.getFullName(),
                    user.getRoles(), user.getProvider(), user.isEmailVerified(),
                    UserResponse.UserStatusView.valueOf(user.getStatus().name()),
                    user.getLastLoginAt(), user.getCreatedAt());
            return new AdminUserResponse(
                    base.id(), base.email(), base.fullName(), base.role(), base.roles(),
                    base.status().name(),
                    // "Habilitada" es la misma condicion que autoriza el acceso:
                    // una cuenta bloqueada o sin confirmar no puede iniciar sesion.
                    user.getStatus() == UserStatus.ACTIVE
                            && !user.isLocked(Instant.now()),
                    base.emailVerified(), base.provider().name(),
                    base.lastLoginAt(), base.createdAt());
        }
    }
}
