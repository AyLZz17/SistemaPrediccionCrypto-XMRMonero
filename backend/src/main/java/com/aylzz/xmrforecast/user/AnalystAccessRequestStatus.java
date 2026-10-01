package com.aylzz.xmrforecast.user;

/** Estados del ciclo de vida de una solicitud de acceso a ANALYST. */
public enum AnalystAccessRequestStatus {
    /** En espera de decisión del ADMIN. */
    PENDING,
    /** Aprobada: el usuario ha sido promovido a ANALYST. */
    APPROVED,
    /** Rechazada por el ADMIN. */
    REJECTED,
    /** Revocada por el ADMIN tras haber sido aprobada. */
    REVOKED;
}