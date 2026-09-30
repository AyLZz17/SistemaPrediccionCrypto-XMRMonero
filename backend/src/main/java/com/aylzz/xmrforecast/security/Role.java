package com.aylzz.xmrforecast.security;

/**
 * Roles de la aplicacion (minimo privilegio, R-27).
 *
 * <p>VIEWER: lectura. ANALYST: lectura + ejecucion de experimentos y predicciones.
 * ADMIN: todo, incluida la promocion de campeon, gestion de usuarios y auditoria.
 */
public enum Role {
    VIEWER,
    ANALYST,
    ADMIN;

    public static final String CLAIM = "roles";

    /**
     * Normaliza el nombre del rol sin depender del locale: {@code toUpperCase()}
     * con el locale por defecto falla en Troubles (p. ej. "i" -> "I" con letra
     * pontia), lo que rompia la resolucion de roles.
     */
    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rol vacio");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (Role role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Rol desconocido");
    }
}