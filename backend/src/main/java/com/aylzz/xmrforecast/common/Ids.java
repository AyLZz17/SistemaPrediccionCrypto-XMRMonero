package com.aylzz.xmrforecast.common;

/**
 * Identificadores publicos opacos (R-38).
 *
 * <p>La API publica los identificadores como cadenas, no como numeros. El cliente
 * debe tratarlos como identificadores opacos: asi no depende del rango ni de la
 * precision de la representacion numerica de JavaScript y no puede suponer que
 * un id de base de datos es un entero seguro.
 *
 * <p>La base de datos sigue usando {@code BIGSERIAL}; la conversion ocurre
 * exclusivamente al serializar. Los {@code @PathVariable} aceptan ambos formatos
 * (ver {@link QueryParams#id(String)}), de modo que la API tolera que un cliente
 * los reenvie tal cual los recibio.
 */
public final class Ids {

    private Ids() {
    }

    /** Convierte un id de base de datos a su forma publica. {@code null} se propaga. */
    public static String of(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Convierte un id que nunca deberia ser nulo. Si lo es, es un fallo de
     * integridad: se lanza en lugar de devolver {@code "null"} como texto.
     */
    public static String required(Long value) {
        if (value == null) {
            throw new IllegalStateException("Se intento publicar un id nulo: el recurso no fue persistido");
        }
        return String.valueOf(value);
    }
}
