-- =====================================================================
-- XMR-Forecast :: datos de referencia (roles y permisos)
-- NO contiene secretos ni datos reales de mercado (R-14).
-- Las cuentas de prueba se crean por migracion V3 solo en el perfil de test.
-- =====================================================================

INSERT INTO roles (code, description) VALUES
  ('VIEWER',  'Lectura de mercado, predicciones y metricas'),
  ('ANALYST', 'Ejecuta experimentos y lanza predicciones'),
  ('ADMIN',   'Administracion de usuarios, promocion de campeon y auditoria');

INSERT INTO permissions (code, description, resource, action) VALUES
  ('market:read',      'Consultar datos de mercado',            'market',      'READ'),
  ('predictions:read', 'Consultar predicciones',                'predictions', 'READ'),
  ('predictions:write','Solicitar predicciones',                'predictions', 'WRITE'),
  ('metrics:read',     'Consultar metricas y comparaciones',    'metrics',     'READ'),
  ('datasets:read',    'Consultar versiones de dataset',        'datasets',    'READ'),
  ('datasets:write',   'Registrar versiones de dataset',        'datasets',    'WRITE'),
  ('experiments:read', 'Consultar experimentos',                'experiments', 'READ'),
  ('experiments:write','Crear y ejecutar experimentos',         'experiments', 'WRITE'),
  ('models:read',      'Consultar modelos y versiones',         'models',      'READ'),
  ('models:promote',   'Promover un modelo a campeon',          'models',      'PROMOTE'),
  ('jobs:read',        'Consultar el monitor de trabajos',      'jobs',        'READ'),
  ('jobs:cancel',      'Cancelar trabajos',                     'jobs',        'CANCEL'),
  ('notifications:read','Consultar notificaciones',              'notifications','READ'),
  ('users:read',       'Consultar usuarios',                    'users',       'READ'),
  ('users:write',      'Asignar roles a usuarios',              'users',       'WRITE'),
  ('audit:read',       'Consultar la auditoria',                'audit',       'READ');

-- Minimo privilegio (R-27): VIEWER solo lee; ANALYST anade ejecucion;
-- ADMIN es el unico con promocion de campeon, gestion de usuarios y auditoria.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'VIEWER' AND p.action = 'READ'
  AND p.resource IN ('market', 'predictions', 'metrics', 'datasets', 'experiments', 'models', 'jobs', 'notifications');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ANALYST' AND p.action = 'READ'
  AND p.resource IN ('market', 'predictions', 'metrics', 'datasets', 'experiments', 'models', 'jobs', 'notifications');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ANALYST'
  AND (p.action IN ('WRITE', 'CANCEL') AND p.resource IN ('predictions', 'datasets', 'experiments', 'jobs'));

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN';