package com.aylzz.xmrforecast.user;

import com.aylzz.xmrforecast.common.ApiException;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import com.aylzz.xmrforecast.security.Role;
import com.aylzz.xmrforecast.user.AnalystAccessRequestStatus;
import com.aylzz.xmrforecast.user.dto.AnalystAccessRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Set;

/**
 * API para solicitudes de acceso al rol ANALYST.
 *
 * <p>Seguridad por ruta (R-35):
 * <ul>
 *   <li>Crear/Ver propia solicitud: autenticado (VIEWER+)</li>
 *   <li>Listar/Aprobar/Rechazar/Revocar: solo ADMIN</li>
 * </ul>
 *
 * <p>La autorizacion se verifica en el servidor; nunca confiar en el frontend.
 */
@Tag(name = "Solicitudes de acceso ANALYST")
@RestController
@RequestMapping("/api/v1/analyst-access-requests")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class AnalystAccessRequestController {

    private final AnalystAccessRequestService service;

    public AnalystAccessRequestController(AnalystAccessRequestService service) {
        this.service = service;
    }

    /**
     * Crea una nueva solicitud de acceso a ANALYST.
     * Solo usuarios con rol VIEWER pueden solicitar.
     */
    @Operation(summary = "Solicita acceso al rol ANALYST",
            description = "El usuario debe ser VIEWER. Requiere confirmar todos los puntos del cuestionario.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Solicitud creada"),
            @ApiResponse(responseCode = "400", description = "Datos invalidos (checkboxes obligatorios)"),
            @ApiResponse(responseCode = "409", description = "Ya tiene solicitud pendiente, ya es ANALYST, o politica no permite re-solicitar"),
            @ApiResponse(responseCode = "401", description = "Sin autenticar"),
            @ApiResponse(responseCode = "403", description = "Rol no es VIEWER")
    })
    @PostMapping
    public AnalystAccessRequests.CreateResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AnalystAccessRequests.CreateRequest request) {
        // Verificar rol VIEWER (el filtro @PreAuthorize no lo hace, solo autenticado)
        if (!user.hasAtLeast(Role.VIEWER) || user.hasAtLeast(Role.ANALYST)) {
            throw ApiException.forbidden("FORBIDDEN", "Solo usuarios VIEWER pueden solicitar acceso a ANALYST.");
        }
        return service.create(user.id(), request);
    }

    /**
     * Obtiene la solicitud del usuario autenticado (si existe).
     */
    @Operation(summary = "Obtiene mi solicitud de acceso a ANALYST")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud encontrada (o null si no existe)"),
            @ApiResponse(responseCode = "401", description = "Sin autenticar")
    })
    @GetMapping("/my")
    public AnalystAccessRequests.MyRequestResponse getMyRequest(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.getMyRequest(user.id());
    }

    /**
     * Lista todas las solicitudes (solo ADMIN).
     */
    @Operation(summary = "Lista todas las solicitudes de acceso a ANALYST",
            description = "Solo ADMIN. Incluye filtro por estado opcional.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de solicitudes"),
            @ApiResponse(responseCode = "403", description = "El rol no es ADMIN")
    })
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AnalystAccessRequests.AdminListResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String status) {
        Pageable pageable = PageRequest.of(QueryParams.page(page), QueryParams.size(size, QueryParams.MAX_PAGE_SIZE));
        if (status != null && !status.isBlank()) {
            try {
                AnalystAccessRequestStatus s = AnalystAccessRequestStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
                return service.listByStatus(s, pageable);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_STATUS", "Estado invalido: " + status);
            }
        }
        return service.list(pageable);
    }

    /**
     * ADMIN aprueba la solicitud: cambia rol a ANALYST y revoca sesiones.
     */
    @Operation(summary = "Aprueba una solicitud de acceso a ANALYST",
            description = "Cambia el rol del usuario a ANALYST y revoca sus sesiones. Solo ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud aprobada"),
            @ApiResponse(responseCode = "400", description = "Motivo obligatorio"),
            @ApiResponse(responseCode = "403", description = "No es ADMIN"),
            @ApiResponse(responseCode = "404", description = "Solicitud no encontrada"),
            @ApiResponse(responseCode = "409", description = "Estado invalido o auto-aprobacion")
    })
    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public AnalystAccessRequests.DecisionResponse approve(
            @PathVariable String id,
            @Valid @RequestBody AnalystAccessRequests.DecisionRequest decision,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.approve(QueryParams.id(id), user.id(), user.role().name(), decision);
    }

    /**
     * ADMIN rechaza la solicitud.
     */
    @Operation(summary = "Rechaza una solicitud de acceso a ANALYST",
            description = "Exige motivo. Solo ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Solicitud rechazada"),
            @ApiResponse(responseCode = "400", description = "Motivo obligatorio"),
            @ApiResponse(responseCode = "403", description = "No es ADMIN"),
            @ApiResponse(responseCode = "404", description = "Solicitud no encontrada"),
            @ApiResponse(responseCode = "409", description = "Estado invalido")
    })
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public AnalystAccessRequests.DecisionResponse reject(
            @PathVariable String id,
            @Valid @RequestBody AnalystAccessRequests.DecisionRequest decision,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.reject(QueryParams.id(id), user.id(), user.role().name(), decision);
    }

    /**
     * ADMIN revoca una solicitud aprobada (quita rol ANALYST).
     */
    @Operation(summary = "Revoca acceso ANALYST aprobado",
            description = "Quita el rol ANALYST del usuario y revoca sus sesiones. Solo ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Acceso revocado"),
            @ApiResponse(responseCode = "400", description = "Motivo obligatorio"),
            @ApiResponse(responseCode = "403", description = "No es ADMIN"),
            @ApiResponse(responseCode = "404", description = "Solicitud no encontrada"),
            @ApiResponse(responseCode = "409", description = "Estado invalido o usuario no es ANALYST")
    })
    @PatchMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public AnalystAccessRequests.DecisionResponse revoke(
            @PathVariable String id,
            @Valid @RequestBody AnalystAccessRequests.DecisionRequest decision,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.revoke(QueryParams.id(id), user.id(), user.role().name(), decision);
    }
}