package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.common.Ids;
import com.aylzz.xmrforecast.common.PageResponse;
import com.aylzz.xmrforecast.common.QueryParams;
import com.aylzz.xmrforecast.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de la bandeja de notificaciones del usuario autenticado.
 *
 * <p>Seguridad por ruta (R-35): toda lectura y toda marcacion se acotan a
 * {@code user_id}. Marcar como leida la notificacion de otra persona devuelve 404,
 * no 403: un 403 confirmaria que ese id existe.
 */
@Tag(name = "Notificaciones")
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "Lista las notificaciones del usuario autenticado")
    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public PageResponse<NotificationResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthenticatedUser user) {
        var result = service.list(user.id(), QueryParams.page(page), QueryParams.size(size, MAX_PAGE_SIZE));
        return PageResponse.of(result, NotificationResponse::from);
    }

    @Operation(summary = "Numero de notificaciones no leidas")
    @GetMapping("/unread-count")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public UnreadCount unreadCount(@AuthenticationPrincipal AuthenticatedUser user) {
        return new UnreadCount(service.unreadCount(user.id()));
    }

    @Operation(summary = "Marca una notificacion como leida",
            description = "Idempotente: marcarla dos veces no cambia nada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notificacion marcada"),
            @ApiResponse(responseCode = "404", description = "No existe o no le pertenece")
    })
    @PostMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
    public NotificationResponse markRead(@PathVariable String id,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return NotificationResponse.from(service.markAsRead(
                com.aylzz.xmrforecast.common.QueryParams.id(id), user.id()));
    }

    /** Contador de no leidas. */
    public record UnreadCount(long count) {
    }

    /**
     * Notificacion publicada. {@code read} se deriva de {@code readAt} para que el
     * cliente no tenga que interpretar una marca de tiempo nullable.
     */
    public record NotificationResponse(
            String id,
            String title,
            String body,
            String severity,
            java.time.Instant createdAt,
            boolean read,
            String link
    ) {
        static NotificationResponse from(Notification notification) {
            return new NotificationResponse(
                    Ids.of(notification.getId()),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getSeverity().name(),
                    notification.getCreatedAt(),
                    notification.getReadAt() != null,
                    linkOf(notification));
        }

        /**
         * Enlace interno a la pantalla afectada. Solo se construye con valores que
         * ya estan en la base de datos y se valida el tipo contra una lista
         * blanca: sin esa comprobacion, un {@code resourceType} contaminado
         * produciria un enlace arbitrario (una forma sutil de XSS abierto).
         */
        private static String linkOf(Notification notification) {
            String type = notification.getResourceType();
            String id = notification.getResourceId();
            if (type == null || id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) {
                return null;
            }
            return switch (type) {
                case "PREDICTION" -> "/predictions/" + id;
                case "EXPERIMENT" -> "/experiments/" + id;
                case "JOB" -> "/jobs/" + id;
                case "MODEL" -> "/models/" + id;
                default -> null;
            };
        }
    }
}
