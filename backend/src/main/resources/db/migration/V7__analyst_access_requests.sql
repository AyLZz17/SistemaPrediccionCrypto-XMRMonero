-- V7: solicitudes de acceso a rol ANALYST
-- Permite a VIEWER solicitar promocion; solo ADMIN aprueba/rechaza/revoca.
-- Estados: PENDING, APPROVED, REJECTED, REVOKED.
-- Idempotencia: una solicitud PENDING por usuario evita duplicados.
-- Auditoría completa: creación, aprobación, rechazo, revocación.

CREATE TABLE analyst_access_requests (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    motivo          TEXT        NOT NULL,
    uso_previsto    TEXT        NOT NULL,
    cuestionario_version VARCHAR(32) NOT NULL,
    acepta_riesgos  BOOLEAN     NOT NULL,
    acepta_limitaciones BOOLEAN  NOT NULL,
    acepta_metricas BOOLEAN     NOT NULL,
    acepta_no_garantia BOOLEAN  NOT NULL,
    acepta_no_operaciones BOOLEAN NOT NULL,
    acepta_no_backtesting BOOLEAN NOT NULL,
    acepta_rol_analyst BOOLEAN   NOT NULL,
    acepta_no_rentabilidad BOOLEAN NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decided_by      BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    decided_at      TIMESTAMPTZ,
    decision_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_analyst_access_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    CONSTRAINT uq_analyst_access_requests_user_pending
        UNIQUE (user_id)
        DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX idx_analyst_access_requests_user ON analyst_access_requests (user_id);
CREATE INDEX idx_analyst_access_requests_status ON analyst_access_requests (status, created_at DESC);

-- Trigger para updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER trg_analyst_access_requests_updated_at
    BEFORE UPDATE ON analyst_access_requests
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();