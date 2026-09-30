-- =====================================================================
-- XMR-Forecast :: inicializacion de PostgreSQL
--
-- Solo se ejecuta la PRIMERA vez que se crea el volumen de datos.
-- El esquema NO se crea aqui: lo aplica Flyway desde el backend (R-34).
-- Aqui solo se crean roles/privilegios minimos (R-27) y extensiones.
-- =====================================================================

-- minimo privilegio: el usuario de la aplicacion no es superusuario ni
-- tiene permiso de CREATE DATABASE.
REVOKE ALL ON DATABASE xmr_forecast FROM PUBLIC;

-- Extensiones. uuid_generate no es necesaria: el backend usa BIGSERIAL.
-- (El bloque se deja documentado por si el esquema evoluciona.)