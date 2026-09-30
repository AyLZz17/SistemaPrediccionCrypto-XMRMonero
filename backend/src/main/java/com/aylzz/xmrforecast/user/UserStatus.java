package com.aylzz.xmrforecast.user;

/** Estados del ciclo de vida de una cuenta. Refleja la tabla {@code users.status}. */
public enum UserStatus {
    /** Registrado, correo aun sin confirmar. */
    PENDING_VERIFICATION,
    /** Operativo: puede autenticarse. */
    ACTIVE,
    /** Bloqueado por administracion o por exceso de intentos fallidos. */
    SUSPENDED,
    /** Baja logica. No se borra fisicamente para preservar la auditoria. */
    DELETED
}