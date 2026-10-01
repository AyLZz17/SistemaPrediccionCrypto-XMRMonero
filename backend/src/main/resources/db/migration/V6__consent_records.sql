-- V6: registro demostrable del consentimiento (Ley 1581 de 2012, art. 8 y 9;
-- GDPR art. 7). Una fila por documento, por aceptacion.
--
-- Se guarda QUIEN acepto, QUE documento, QUE VERSION, CUANDO y desde donde.
-- La version es la clave: sin ella no hay forma de demostrar que el usuario
-- acepto el texto que estaba vigente en ese momento, y una politica nueva no
-- puede presentarse como ya aceptada por una cuenta antigua.
--
-- Aditiva y no destructiva: las cuentas existentes no tienen filas, lo que es
-- honesto: nunca aceptaron este texto. El hueco se cubre en el proximo acceso
-- cuando el servicio lo requiera, no rellenandolo a posteriori.

CREATE TABLE consent_records (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    consent_type VARCHAR(24) NOT NULL,
    version      VARCHAR(32) NOT NULL,
    accepted     BOOLEAN     NOT NULL,
    source       VARCHAR(24) NOT NULL,
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(255),
    accepted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_consent_records_type
        CHECK (consent_type IN ('TERMS', 'DATA_POLICY', 'MARKETING')),
    CONSTRAINT ck_consent_records_source
        CHECK (source IN ('REGISTER', 'GOOGLE_OAUTH', 'EXPLICIT'))
);

CREATE INDEX idx_consent_records_user ON consent_records (user_id, consent_type, accepted_at DESC);
