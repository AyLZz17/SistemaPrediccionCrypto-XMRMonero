-- =====================================================================
-- XMR-Forecast :: esquema inicial
-- Migracion reproducible desde cero. Ejecutada solo por Flyway (R-34).
-- El servicio ML no administra este esquema.
-- =====================================================================

-- ---------------------------------------------------------------- identity
CREATE TABLE roles (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL,
    description  VARCHAR(255) NOT NULL,
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL,
    resource    VARCHAR(64) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT uq_permissions_resource_action UNIQUE (resource, action)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(254) NOT NULL,
    -- NUNCA se almacena la contrasena en claro; solo hashes BCrypt/Argon2 (R-14)
    password_hash       VARCHAR(255),
    full_name           VARCHAR(120) NOT NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified_at   TIMESTAMPTZ,
    provider            VARCHAR(24)  NOT NULL DEFAULT 'LOCAL',
    failed_login_count  INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_VERIFICATION', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_users_provider CHECK (provider IN ('LOCAL', 'GOOGLE')),
    -- una cuenta local exige hash; una social puede no tenerlo
    CONSTRAINT ck_users_local_password CHECK (provider <> 'LOCAL' OR password_hash IS NOT NULL)
);
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_created_at ON users (created_at DESC);

-- Se enlaza por el codigo del rol (no por id) para que el mapeo de la entidad
-- sea un enum legible y no dependa de la clave surrogata.
CREATE TABLE user_roles (
    user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_code VARCHAR(32) NOT NULL REFERENCES roles (code) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_code)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_code);

CREATE TABLE oauth_accounts (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider            VARCHAR(24) NOT NULL,
    provider_subject    VARCHAR(255) NOT NULL,
    provider_email      VARCHAR(254),
    linked_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_oauth_provider_subject UNIQUE (provider, provider_subject)
);
CREATE INDEX idx_oauth_user ON oauth_accounts (user_id);

-- Refresh tokens: solo se guarda el HASH (HMAC-SHA256), nunca el token (R-14)
CREATE TABLE refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL,
    jti             VARCHAR(64) NOT NULL,
    family_id       VARCHAR(64) NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(64),
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(64),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT uq_refresh_tokens_jti UNIQUE (jti)
);
CREATE INDEX idx_refresh_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);

-- Revocacion de access tokens por jti (R: revocacion por jti)
CREATE TABLE revoked_tokens (
    id          BIGSERIAL PRIMARY KEY,
    jti         VARCHAR(64) NOT NULL,
    user_id     BIGINT REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason      VARCHAR(64) NOT NULL,
    CONSTRAINT uq_revoked_tokens_jti UNIQUE (jti)
);
CREATE INDEX idx_revoked_tokens_expires ON revoked_tokens (expires_at);

-- Bloqueo progresivo e investigacion de ataques de credenciales
CREATE TABLE login_attempts (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254),
    user_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    successful    BOOLEAN     NOT NULL,
    failure_reason VARCHAR(64),
    provider      VARCHAR(24) NOT NULL DEFAULT 'LOCAL',
    ip_address    VARCHAR(64),
    user_agent    VARCHAR(255),
    attempted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_login_attempts_email_time ON login_attempts (email, attempted_at DESC);
CREATE INDEX idx_login_attempts_ip_time ON login_attempts (ip_address, attempted_at DESC);

-- Recuperacion / cambio de contrasena (hash, nunca el token en claro)
CREATE TABLE password_reset_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL,
    purpose      VARCHAR(24) NOT NULL DEFAULT 'RESET',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT uq_password_reset_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_purpose CHECK (purpose IN ('RESET', 'VERIFY_EMAIL'))
);
CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id);

CREATE TABLE email_verification_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    CONSTRAINT uq_email_verification_hash UNIQUE (token_hash)
);

-- ------------------------------------------------------------------- audit
CREATE TABLE audit_events (
    id             BIGSERIAL PRIMARY KEY,
    actor_user_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_role     VARCHAR(32),
    action         VARCHAR(64) NOT NULL,
    resource_type  VARCHAR(64) NOT NULL,
    resource_id    VARCHAR(64),
    outcome        VARCHAR(24) NOT NULL,
    ip_address     VARCHAR(64),
    user_agent     VARCHAR(255),
    request_id     VARCHAR(64),
    trace_id       VARCHAR(64),
    details        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);
CREATE INDEX idx_audit_actor_time ON audit_events (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_resource ON audit_events (resource_type, resource_id);
CREATE INDEX idx_audit_created_at ON audit_events (created_at DESC);

-- ------------------------------------------------------------------- market
CREATE TABLE market_data (
    id         BIGSERIAL PRIMARY KEY,
    symbol     VARCHAR(16)  NOT NULL,
    source     VARCHAR(32)  NOT NULL,
    opened_at  TIMESTAMPTZ NOT NULL,
    open       NUMERIC(20, 8) NOT NULL,
    high       NUMERIC(20, 8) NOT NULL,
    low        NUMERIC(20, 8) NOT NULL,
    close      NUMERIC(20, 8) NOT NULL,
    volume     NUMERIC(28, 8),
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_market_data UNIQUE (symbol, source, opened_at),
    CONSTRAINT ck_market_ohlc CHECK (high >= low AND high >= open AND high >= close AND low <= open AND low <= close)
);
CREATE INDEX idx_market_symbol_time ON market_data (symbol, opened_at DESC);

-- ------------------------------------------------------------------ dataset
-- Versionado por checksum + procedencia (R-28)
CREATE TABLE dataset_versions (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(16)  NOT NULL,
    version         VARCHAR(64)  NOT NULL,
    source          VARCHAR(32)  NOT NULL,
    checksum_sha256 CHAR(64)     NOT NULL,
    rows_count      BIGINT       NOT NULL,
    first_date      DATE         NOT NULL,
    last_date       DATE         NOT NULL,
    storage_uri     TEXT,
    provenance      JSONB,
    created_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dataset_version UNIQUE (symbol, version),
    CONSTRAINT ck_dataset_range CHECK (last_date >= first_date),
    CONSTRAINT ck_dataset_rows CHECK (rows_count > 0)
);
CREATE INDEX idx_dataset_checksum ON dataset_versions (checksum_sha256);

-- --------------------------------------------------------------- experiments
CREATE TABLE experiments (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(64)  NOT NULL,
    name           VARCHAR(160) NOT NULL,
    description    TEXT,
    hypothesis     TEXT,
    status         VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    config_yaml    TEXT         NOT NULL,
    config_sha256  CHAR(64)     NOT NULL,
    dataset_version_id BIGINT NOT NULL REFERENCES dataset_versions (id) ON DELETE RESTRICT,
    created_by     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_experiments_code UNIQUE (code),
    CONSTRAINT ck_experiments_status CHECK (status IN ('DRAFT', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE experiment_runs (
    id                  BIGSERIAL PRIMARY KEY,
    experiment_id       BIGINT      NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    run_key             VARCHAR(64) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    task_type           VARCHAR(16) NOT NULL DEFAULT 'REGRESSION',
    seeds               INTEGER[]   NOT NULL DEFAULT '{42}',
    train_ratio         NUMERIC(4,3) NOT NULL DEFAULT 0.700,
    val_ratio           NUMERIC(4,3) NOT NULL DEFAULT 0.150,
    test_ratio          NUMERIC(4,3) NOT NULL DEFAULT 0.150,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       TEXT,
    created_by          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_experiment_runs UNIQUE (experiment_id, run_key),
    CONSTRAINT ck_runs_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_runs_task_type CHECK (task_type IN ('REGRESSION', 'DIRECTION')),
    -- R-01: particion cronologica, la suma debe ser exactamente 1
    CONSTRAINT ck_runs_split CHECK (train_ratio + val_ratio + test_ratio = 1.000)
);
CREATE INDEX idx_runs_experiment ON experiment_runs (experiment_id, created_at DESC);
CREATE INDEX idx_runs_status ON experiment_runs (status);

-- ------------------------------------------------------------------- models
CREATE TABLE models (
    id           BIGSERIAL PRIMARY KEY,
    model_key    VARCHAR(64)  NOT NULL,
    family       VARCHAR(32)  NOT NULL,
    task_type    VARCHAR(16)  NOT NULL DEFAULT 'REGRESSION',
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_models_key UNIQUE (model_key),
    CONSTRAINT ck_models_family CHECK (family IN ('LSTM', 'GRU', 'MOVING_AVERAGE', 'LINEAR_REGRESSION', 'ARIMA')),
    CONSTRAINT ck_models_task_type CHECK (task_type IN ('REGRESSION', 'DIRECTION'))
);

CREATE TABLE model_versions (
    id                BIGSERIAL PRIMARY KEY,
    model_id          BIGINT      NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    version           VARCHAR(64) NOT NULL,
    run_id            BIGINT REFERENCES experiment_runs (id) ON DELETE SET NULL,
    dataset_version_id BIGINT NOT NULL REFERENCES dataset_versions (id) ON DELETE RESTRICT,
    artifact_uri     TEXT        NOT NULL,
    artifact_sha256  CHAR(64)    NOT NULL,
    scaler_uri        TEXT,
    scaler_sha256     CHAR(64),
    config_sha256     CHAR(64)    NOT NULL,
    seed              INTEGER     NOT NULL DEFAULT 42,
    -- R-24: el campeon se elige por VALIDACION, nunca por test
    selected_on       VARCHAR(16) NOT NULL DEFAULT 'VALIDATION',
    is_champion       BOOLEAN     NOT NULL DEFAULT FALSE,
    promoted_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    promoted_at       TIMESTAMPTZ,
    integrity_verified BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_model_versions UNIQUE (model_id, version),
    CONSTRAINT ck_model_version_selected_on CHECK (selected_on IN ('VALIDATION', 'MANUAL', 'TEST'))
);
CREATE UNIQUE INDEX uq_model_versions_single_champion ON model_versions (model_id) WHERE is_champion;
CREATE INDEX idx_model_versions_sha ON model_versions (artifact_sha256);

-- --------------------------------------------------------------------- jobs
CREATE TABLE jobs (
    id                BIGSERIAL PRIMARY KEY,
    job_key           VARCHAR(80)  NOT NULL,
    type              VARCHAR(48)  NOT NULL,
    status            VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    -- clave de idempotencia: impide duplicar predicciones/trabajos (R-11 repo)
    idempotency_key   VARCHAR(128),
    payload           JSONB,
    result            JSONB,
    progress_percent  INTEGER      NOT NULL DEFAULT 0,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    max_attempts      INTEGER      NOT NULL DEFAULT 3,
    error_message     TEXT,
    created_by        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    queued_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    heartbeat_at      TIMESTAMPTZ,
    CONSTRAINT uq_jobs_job_key UNIQUE (job_key),
    CONSTRAINT uq_jobs_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_jobs_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    CONSTRAINT ck_jobs_progress CHECK (progress_percent BETWEEN 0 AND 100)
);
CREATE INDEX idx_jobs_status ON jobs (status, queued_at);
CREATE INDEX idx_jobs_creator ON jobs (created_by, queued_at DESC);
CREATE INDEX idx_jobs_heartbeat ON jobs (heartbeat_at);

-- -------------------------------------------------------------- predictions
CREATE TABLE predictions (
    id                 BIGSERIAL PRIMARY KEY,
    model_version_id   BIGINT      NOT NULL REFERENCES model_versions (id) ON DELETE RESTRICT,
    symbol             VARCHAR(16) NOT NULL,
    target_date        DATE        NOT NULL,
    predicted_close    NUMERIC(20, 8) NOT NULL,
    predicted_direction VARCHAR(8) NOT NULL,
    actual_close       NUMERIC(20, 8),
    confidence         NUMERIC(6, 5),
    -- trazabilidad completa de cada prediccion (R-21/R-28)
    dataset_version_id BIGINT NOT NULL REFERENCES dataset_versions (id) ON DELETE RESTRICT,
    artifact_sha256    CHAR(64)    NOT NULL,
    config_sha256      CHAR(64)    NOT NULL,
    seed               INTEGER     NOT NULL DEFAULT 42,
    trace              JSONB,
    requested_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    request_id         VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_predictions UNIQUE (model_version_id, symbol, target_date),
    CONSTRAINT ck_predictions_direction CHECK (predicted_direction IN ('UP', 'DOWN', 'FLAT'))
);
CREATE INDEX idx_predictions_target ON predictions (symbol, target_date DESC);
CREATE INDEX idx_predictions_model ON predictions (model_version_id, created_at DESC);
CREATE INDEX idx_predictions_requester ON predictions (requested_by, created_at DESC);

-- ------------------------------------------------------------------ metrics
CREATE TABLE metrics (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT      NOT NULL REFERENCES experiment_runs (id) ON DELETE CASCADE,
    model_version_id    BIGINT REFERENCES model_versions (id) ON DELETE CASCADE,
    split               VARCHAR(16) NOT NULL,
    mae                 NUMERIC(20, 10),
    rmse                NUMERIC(20, 10),
    mape                NUMERIC(20, 10),
    direction_accuracy  NUMERIC(6, 5),
    precision_up        NUMERIC(6, 5),
    recall_up           NUMERIC(6, 5),
    confusion_matrix    JSONB,
    n_samples           INTEGER,
    n_seeds             INTEGER NOT NULL DEFAULT 1,
    stddev              JSONB,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_metrics_split CHECK (split IN ('TRAIN', 'VALIDATION', 'TEST')),
    CONSTRAINT ck_metrics_metrics CHECK (mae IS NULL OR mae >= 0)
);
-- Una metrica por (run, split, modelo). COALESCE porque model_version_id es
-- nullable para baselines sin artefacto persistido; en Postgres UNIQUE trataria
-- cada NULL como distinto y permitiria duplicados.
CREATE UNIQUE INDEX uq_metrics_run_split_model
    ON metrics (run_id, split, COALESCE(model_version_id, 0));
CREATE INDEX idx_metrics_run ON metrics (run_id, split);
CREATE INDEX idx_metrics_model ON metrics (model_version_id);

-- ------------------------------------------------------------- notifications
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(48) NOT NULL,
    title      VARCHAR(160) NOT NULL,
    body       TEXT,
    severity   VARCHAR(16) NOT NULL DEFAULT 'INFO',
    resource_type VARCHAR(48),
    resource_id   VARCHAR(64),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_notifications_severity CHECK (severity IN ('INFO', 'SUCCESS', 'WARNING', 'ERROR'))
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;