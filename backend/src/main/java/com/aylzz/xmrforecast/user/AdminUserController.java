package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import com.aylzz.xmrforecast.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * API de administracion de cuentas.
 *
 * <p>Seguridad por ruta (R-35): toda la superficie es ADMIN. Estas rutas
 * enumeran cuentas y cambian permisos, que es exactamente el objetivo de OWASP
 * A01 y A07. Un VIEWER que las alcance recibe 403 del filtro de autorizacion de
 * metodo, antes de entrar al controlador.
 */
@Tag(name = "Administracion")
@RestController
@RequestMapping("/api/v1/users")
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserAdminService service;

    public AdminUserController(UserAdminService service) {
        this.service = service;
    }

    @Operation(summary = "Lista las cuentas registradas",
            description = "Solo ADMIN. Nunca incluye hashes de contrasena ni tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de cuentas"),
            @ApiResponse(responseCode = "403", description = "El rol no es ADMIN")
    })
    @GetMapping
    public PageResponse<UserAdminService.AdminUserResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.list(page, size);
    }

    @Operation(summary = "Detalle de una cuenta")
    @ApiResponse(responseCode = "404", description = "El usuario no existe")
    @GetMapping("/{id}")
    public UserAdminService.AdminUserResponse get(@PathVariable String id) {
        return service.get(QueryParams.id(id));
    }

    @Operation(summary = "Cambia el rol de una cuenta",
            description = "Sustituye el conjunto de roles completo, revoca las sesiones activas "
                    + "del usuario afectado y protege al ultimo administrador del sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles actualizados"),
            @ApiResponse(responseCode = "400", description = "Conjunto de roles vacio"),
            @ApiResponse(responseCode = "404", description = "El usuario no existe"),
            @ApiResponse(responseCode = "409", description = "Seria el ultimo ADMIN, o auto-degradacion")
    })
    @PatchMapping("/{id}/role")
    public UserAdminService.AdminUserResponse changeRole(
            @PathVariable String id,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.changeRoles(QueryParams.id(id), toRoles(request),
                user.id(), user.role().name());
    }

    /**
     * Acepta tanto el conjunto completo como un unico rol.
     *
     * <p>El cliente historico envia {@code {"role": "ADMIN"}}; uno recien escrito
     * envia {@code {"roles": ["ANALYST", "ADMIN"]}}. Aceptar ambos evita que
     * actualizar el cliente rompa el contrato y devuelva 400 a un usuario que
     * esta haciendo algo legitimo.
     */
    private static Set<Role> toRoles(ChangeRoleRequest request) {
        Set<Role> roles = new LinkedHashSet<>();
        // Jackson ya convierte el texto al enum y responde 400 ante un valor
        // desconocido, asi que aqui no hay nada que interpretar. Con
        // `Role.valueOf` sobre texto libre, un "administrador" lanzaba
        // IllegalArgumentException y el manejador global no la traduceva: un
        // error de escritura del cliente terminaba en 500 INTERNAL_ERROR.
        if (request.roles() != null) {
            roles.addAll(request.roles());
        }
        if (request.role() != null) {
            roles.add(request.role());
        }
        return roles;
    }

    /**
     * Cuerpo del cambio de rol. Acepta {@code role} o {@code roles}.
     *
     * <p>Los roles se declaran como el enum y no como texto: el contrato queda
     * abierto en el esquema y Jackson responde 400 ante un valor invalido. El
     * tamano esta acotado porque el conjunto se guarda en {@code user_roles} y
     * una lista sin limite seria una via de escritura gratuita.
     */
    public record ChangeRoleRequest(
            Role role,
            @Size(max = 3, message = "Un usuario no puede tener mas de 3 roles.")
            Set<@NotNull Role> roles
    ) {
    }
}
