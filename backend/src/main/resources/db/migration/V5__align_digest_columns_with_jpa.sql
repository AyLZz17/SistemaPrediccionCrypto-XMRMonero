-- =====================================================================
-- XMR-Forecast :: V5 - alinea CHAR(64) con el mapeo JPA
--
-- POR QUE
--   V1 declaro cinco columnas de digest como CHAR(64) (bpchar en PostgreSQL).
--   Las entidades las mapean como `@Column(length = 64) String`, que Hibernate
--   traduce a VARCHAR(64). Con `spring.jpa.hibernate.ddl-auto: validate` —que es
--   exactamente lo que exige R-34, validar en vez de crear— esa discrepancia
--   hace que el backend NO ARRANQUE:
--
--     SchemaManagementException: found [bpchar (Types#CHAR)],
--     but expecting [varchar(64) (Types#VARCHAR)]
--
--   El fallo no lo detecta `mvn test` (no hay base de datos en las pruebas
--   unitarias) ni `mvn compile`: solo aparece al levantar la aplicacion.
--
-- POR QUE VARCHAR Y NO CHAR
--   Un digest SHA-256 en hexadecimal tiene siempre 64 caracteres, asi que
--   CHAR(64) no aporta nada. Y el relleno con espacios de CHAR produce
--   sorpresas reales: una comparacion entre un CHAR y un VARCHAR compara
--   tambien los espacios de relleno, y un `LIKE` o un `substr` puede
--   devolver la cadena con espacios al final. VARCHAR(64) no tiene ninguno de
--   esos problemas.
--
--   No se edita V1: una migracion ya aplicada tiene su checksum registrado en
--   `flyway_schema_history` y cambiarla haria que todas las instalaciones
--   existentes dejasen de validar. Las correcciones van en una migracion nueva.
--
--   El inventario se hizo consultando information_schema, no a ojo sobre V1.
--   Hibernate valida tabla por tabla y aborta en la primera discrepancia, de
--   modo que descubrir las columnas de una en una habria costado un arranque
--   fallido por cada una.
-- =====================================================================

ALTER TABLE dataset_versions
    ALTER COLUMN checksum_sha256 TYPE VARCHAR(64);

ALTER TABLE experiments
    ALTER COLUMN config_sha256 TYPE VARCHAR(64);

ALTER TABLE model_versions
    ALTER COLUMN artifact_sha256 TYPE VARCHAR(64),
    ALTER COLUMN scaler_sha256   TYPE VARCHAR(64),
    ALTER COLUMN config_sha256   TYPE VARCHAR(64);

ALTER TABLE predictions
    ALTER COLUMN artifact_sha256 TYPE VARCHAR(64),
    ALTER COLUMN config_sha256   TYPE VARCHAR(64);
