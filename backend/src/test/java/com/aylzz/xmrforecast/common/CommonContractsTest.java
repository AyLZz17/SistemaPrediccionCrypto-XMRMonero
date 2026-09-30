package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pruebas del sobre de paginacion y del contrato de error. */
class CommonContractsTest {

    @Test
    @DisplayName("La paginacion expone total y totalPages de forma estable")
    void pageResponseExposesTotals() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 10);

        var response = PageResponse.of(page);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(10);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("La ultima pagina informa hasNext=false")
    void lastPageHasNoNext() {
        var page = new PageImpl<>(List.of("x"), PageRequest.of(4, 2), 9);

        assertThat(PageResponse.of(page).hasNext()).isFalse();
    }

    @Test
    @DisplayName("El mapeo de entidades a DTO no pierde elementos")
    void mapsEntitiesToDtos() {
        var page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 10), 3);

        var response = PageResponse.of(page, v -> "modelo-" + v);

        assertThat(response.items()).containsExactly("modelo-1", "modelo-2", "modelo-3");
        assertThat(response.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("El error no filtra detalles internos")
    void errorOmitsSensitiveDetail() {
        var error = ApiError.of(400, "Bad Request", "CODE", "Mensaje seguro", "/api/v1", "req-1");

        assertThat(error.code()).isEqualTo("CODE");
        assertThat(error.requestId()).isEqualTo("req-1");
        assertThat(error.fieldErrors()).isNull();
        // No existe ningun campo que exponga el valor rechazado ni el stack trace.
        assertThat(error.toString()).doesNotContain("at com.aylzz");
    }

    @Test
    @DisplayName("Los identificadores de correlacion se sanean contra inyeccion en logs")
    void sanitizesCorrelationIds() {
        String malicious = "abc\r\nGET /evil HTTP/1.1\r\nX-Injected: 1";
        String sanitized = RequestContext.sanitize(malicious);

        assertThat(sanitized).doesNotContain("\n").doesNotContain("\r");
        assertThat(sanitized).isNotEqualTo(malicious);

        // Un identificador legitimo se conserva tal cual para no romper la correlacion.
        assertThat(RequestContext.sanitize("req-abc_123.x")).isEqualTo("req-abc_123.x");
        // Vacio o nulo genera uno nuevo.
        assertThat(RequestContext.sanitize(null)).isNotBlank();
        assertThat(RequestContext.sanitize("")).isNotBlank();
    }

    @Test
    @DisplayName("La violacion de campo no incluye el valor rechazado")
    void fieldViolationHidesValue() {
        var violation = new ApiError.FieldViolation("password", "La contrasena es obligatoria.");

        assertThat(violation.field()).isEqualTo("password");
        assertThat(violation.message()).doesNotContain("secret");
    }
}