package com.aylzz.xmrforecast.user;

/**
 * Documentos cuya aceptacion se registra de forma demostrable.
 *
 * <p>La distincion entre {@code TERMS} y {@code DATA_POLICY} no es cosmica:
 * los dos son obligatorios para crear una cuenta y se guardan en filas
 * separadas, de modo que se puede probar que cada uno fue aceptado con su
 * version correspondiente. {@code MARKETING} es opcional y su ausencia de fila
 * significa "no aceptado"; una fila con {@code accepted=false} deja constancia
 * de una revocatoria explicita.
 */
public enum ConsentType {
    /** Terminos y condiciones de uso. */
    TERMS,
    /** Politica de tratamiento de datos personales. */
    DATA_POLICY,
    /** Comunicaciones comerciales (opcional). */
    MARKETING
}
