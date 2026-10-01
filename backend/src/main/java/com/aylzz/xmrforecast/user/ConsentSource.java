package com.aylzz.xmrforecast.user;

/** Canal por el que se recogio el consentimiento. */
public enum ConsentSource {
    /** Formulario de registro con correo y contrasena. */
    REGISTER,
    /** Flujo OAuth de Google: el consentimiento viaja en el state. */
    GOOGLE_OAUTH,
    /** Aceptacion explicita posterior (bandeja o panel de cuenta). */
    EXPLICIT
}
