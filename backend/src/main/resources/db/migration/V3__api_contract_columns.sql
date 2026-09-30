-- =====================================================================
-- XMR-Forecast :: V3 - alinea el esquema con el contrato publico de la API
--
-- Por que una migracion y no un cambio en la entidad: R-34 establece que
-- Flyway es la unica fuente del esquema, y `ddl-auto: validate` hace que
-- cualquier desalineacion entre entidad y base de datos impida el arranque.
--
-- 1. predictions.status
--    El dominio distingue una prediccion pendiente de una ya calculada. Sin
--    esta columna el cliente no puede diferenciar "aun no hay resultado" de
--    "el modelo no dio valor", que son dos situaciones opuestas para el usuario.
-- 2. experiments.task_type
--    D-04: el proyecto implementa regresion (cierre t+1) y direccion. La tarea
--    era una propiedad de la corrida;.sin ella a nivel de experimento no se
--    puede filtrar la comparacion de metricas por tarea.
-- =====================================================================

ALTER TABLE predictions
    ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'PENDING';

-- Las filas anteriores a esta migracion ya tienen un cierre previsto persistido:
-- se marcan como listas para no presentarlas como trabajo en curso.
UPDATE predictions SET status = 'READY' WHERE predicted_close IS NOT NULL;
UPDATE predictions SET status = 'FAILED' WHERE predicted_close IS NULL;

ALTER TABLE predictions
    ADD CONSTRAINT ck_predictions_status CHECK (status IN ('PENDING', 'READY', 'FAILED'));

CREATE INDEX idx_predictions_status ON predictions (status, created_at DESC);

ALTER TABLE experiments
    ADD COLUMN task_type VARCHAR(16) NOT NULL DEFAULT 'REGRESSION';

ALTER TABLE experiments
    ADD CONSTRAINT ck_experiments_task_type CHECK (task_type IN ('REGRESSION', 'DIRECTION'));
