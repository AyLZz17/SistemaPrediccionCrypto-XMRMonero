package com.aylzz.xmrforecast.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * Manejo global de errores. Toda respuesta de error sale con la forma de
 * {@link ApiError} e incluye el {@code requestId} para correlacion.
 * No se exponen nunca mensajes internos, SQL ni stack traces (OWASP API5).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        log.warn("Error de negocio code={} status={}", ex.getCode(), ex.getStatus());
        return build(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        ApiError body = ApiError.withFields(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "VALIDATION_FAILED",
                "La peticion no cumple las reglas de validacion.",
                path(), RequestContext.requestId(), violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(
                        String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiError.withFields(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "CONSTRAINT_VIOLATION",
                "La peticion no cumple las restricciones declaradas.",
                path(), RequestContext.requestId(), violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        // No se propaga la causa interna del deserializador.
        return build(HttpStatus.BAD_REQUEST.value(), "MALFORMED_JSON",
                "El cuerpo de la peticion no es JSON valido.");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParameter(Exception ex) {
        return build(HttpStatus.BAD_REQUEST.value(), "INVALID_PARAMETER",
                "Uno o mas parametros de la peticion son invalidos.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED.value(), "METHOD_NOT_ALLOWED",
                "El metodo HTTP no esta permitido para este recurso.");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException ex) {
        return build(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND",
                "El recurso solicitado no existe.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        // No se registra el detalle de SQL: puede contener valores de otras filas.
        log.warn("Violacion de integridad de datos");
        return build(HttpStatus.CONFLICT.value(), "CONSTRAINT_VIOLATION",
                "La operacion viola una restriccion de integridad de datos.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "No tiene permisos para acceder a este recurso.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED",
                "Se requieren credenciales validas para acceder a este recurso.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Se registra el stack completo solo en el log del servidor, nunca en la respuesta.
        log.error("Error no controlado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "Se produjo un error interno. Intente de nuevo mas tarde.");
    }

    private ApiError.FieldViolation toViolation(FieldError error) {
        String message = error.getDefaultMessage() == null
                ? "valor invalido"
                : error.getDefaultMessage();
        return new ApiError.FieldViolation(error.getField(), message);
    }

    private ResponseEntity<ApiError> build(int status, String code, String message) {
        HttpStatus resolved = HttpStatus.resolve(status);
        String reason = resolved != null ? resolved.getReasonPhrase() : "Error";
        return ResponseEntity.status(status)
                .body(ApiError.of(status, reason, code, message, path(), RequestContext.requestId()));
    }

    /** Ruta actual recuperada del contexto de la peticion. */
    private String path() {
        var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (request instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            return attrs.getRequest().getRequestURI();
        }
        return "";
    }
}