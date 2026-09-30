package com.aylzz.xmrforecast.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Normalizacion de parametros de consulta.
 *
 * <p>La tolerancia de formato de fecha no es un detalle cosmetico: el cliente de
 * la interfaz envia {@code YYYY-MM-DD} y otras integraciones envian ISO-8601
 * completo. Un 400 por el formato dejaria la pagina de mercado vacia segun quien
 * la abriera.
 */
class QueryParamsTest {

    @Nested
    @DisplayName("Fechas")
    class Dates {

        @Test
        void acceptsAPlainDateAsStartOfDayUtc() {
            assertThat(QueryParams.start("2026-06-01"))
                    .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        }

        @Test
        @DisplayName("una fecha sin hora como limite incluye el dia completo")
        void aPlainDateAsEndIncludesTheWholeDay() {
            // Sin esto, ?from=2026-06-01&to=2026-06-01 devolveria solo la medianoche.
            assertThat(QueryParams.end("2026-06-01"))
                    .isAfter(QueryParams.start("2026-06-01"));
        }

        @Test
        void acceptsFullIsoInstants() {
            assertThat(QueryParams.start("2026-06-01T10:30:00Z"))
                    .isEqualTo(Instant.parse("2026-06-01T10:30:00Z"));
        }

        @Test
        void acceptsIsoInstantsWithAnOffset() {
            assertThat(QueryParams.start("2026-06-01T12:30:00+02:00"))
                    .isEqualTo(Instant.parse("2026-06-01T10:30:00Z"));
        }

        @Test
        void acceptsLocalDateTimesAsUtc() {
            assertThat(QueryParams.start("2026-06-01T10:30:00"))
                    .isEqualTo(Instant.parse("2026-06-01T10:30:00Z"));
        }

        @Test
        void acceptsEpochMilliseconds() {
            assertThat(QueryParams.start("1780000000000"))
                    .isEqualTo(Instant.ofEpochMilli(1780000000000L));
        }

        @Test
        void treatsNullAndBlankAsAbsent() {
            assertThat(QueryParams.start(null)).isNull();
            assertThat(QueryParams.start("   ")).isNull();
            assertThat(QueryParams.end(null)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"ayer", "2026-13-45", "999999999999999999", "0"})
        @DisplayName("una fecha ilegible es 400, no un 500 ni un rango silencioso")
        void rejectsUnparseableDates(String raw) {
            assertThatThrownBy(() -> QueryParams.instant(raw))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(400));
        }
    }

    @Nested
    @DisplayName("Identificadores")
    class Identifiers {

        @Test
        void acceptsBothPublishedForms() {
            // La API publica el id como cadena; reenviarlo tal cual debe funcionar.
            assertThat(QueryParams.id("42")).isEqualTo(42L);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "-1", "0", "1.5", "99999999999999999999"})
        @DisplayName("un id ilegible se rechaza con INVALID_ID")
        void rejectsAnythingElseWithAStableCode(String raw) {
            assertThatThrownBy(() -> QueryParams.id(raw))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_ID"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("un id ausente se distingue del id invalido")
        void distinguishesAMissingIdFromAnInvalidOne(String raw) {
            // Un 404 de recurso no encontrado y un 400 de entrada invalida son
            // cosas distintas; mezclarlas hace imposible diagnosticar el cliente.
            assertThatThrownBy(() -> QueryParams.id(raw))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                            .isEqualTo("INVALID_PARAMETER"));
        }
    }

    @Nested
    @DisplayName("Paginacion")
    class Paging {

        @Test
        void neverReturnsANegativePage() {
            assertThat(QueryParams.page(null)).isZero();
            assertThat(QueryParams.page(-5)).isZero();
            assertThat(QueryParams.page(3)).isEqualTo(3);
        }

        @Test
        @DisplayName("acota el tamano de pagina (OWASP API4)")
        void capsPageSize() {
            assertThat(QueryParams.size(1_000_000, 200)).isEqualTo(200);
            assertThat(QueryParams.size(0, 200)).isEqualTo(QueryParams.DEFAULT_PAGE_SIZE);
            assertThat(QueryParams.size(-1, 200)).isEqualTo(QueryParams.DEFAULT_PAGE_SIZE);
            assertThat(QueryParams.size(50, 200)).isEqualTo(50);
        }
    }

    @Test
    void normalisesSymbols() {
        assertThat(QueryParams.symbol("  xmr-usd ")).isEqualTo("XMR-USD");
        assertThat(QueryParams.symbol(null)).isEqualTo("XMR-USD");
        assertThat(QueryParams.symbol("btc-usd")).isEqualTo("BTC-USD");
    }
}
