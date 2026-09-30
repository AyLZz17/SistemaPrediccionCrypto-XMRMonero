#!/usr/bin/env bash
# =====================================================================
# XMR-Forecast :: backup y restauracion de PostgreSQL
#
# Seccion 9: backups, restauracion probada, retencion y datos separados.
#
# Uso:
#   bash docker/backup.sh backup    [directorio]
#   bash docker/backup.sh restore   <fichero-dump>
#   bash docker/backup.sh verify    <fichero-dump>
#   bash docker/backup.sh list
#
# El dump se crea con pg_dump en formato custom (-Fc), que permite restaurar
# de forma selectiva y verificar la integridad con pg_restore --list.
# =====================================================================
set -euo pipefail

CONTAINER="${POSTGRES_CONTAINER:-xmr-postgres}"
PGUSER="${POSTGRES_USER:-xmr}"
PGDATABASE="${POSTGRES_DB:-xmr_forecast}"
BACKUP_ROOT="${BACKUP_ROOT:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

log()  { printf '\033[0;36m[backup]\033[0m %s\n' "$1"; }
fail() { printf '\033[0;31m[backup] ERROR:\033[0m %s\n' "$1" >&2; exit 1; }

require_container() {
  docker inspect "$CONTAINER" >/dev/null 2>&1 \
    || fail "El contenedor '${CONTAINER}' no existe. Levanta el sistema con: docker compose up -d"
}

do_backup() {
  local dest_dir="${1:-$BACKUP_ROOT}"
  require_container
  mkdir -p "$dest_dir"

  local out="${dest_dir}/xmr_forecast_${TIMESTAMP}.dump"
  local meta="${out}.sha256"

  log "Volcando '${PGDATABASE}' desde '${CONTAINER}'"
  docker exec "$CONTAINER" pg_dump \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --format custom \
    --compress 9 \
    --no-owner \
    --no-privileges \
    --file "/tmp/${out##*/}" 2>/dev/null \
    && docker cp "${CONTAINER}:/tmp/${out##*/}" "$out" \
    && docker exec "$CONTAINER" rm -f "/tmp/${out##*/}" \
    || {
      # Fallback para clientes que no soportan 'docker cp': vuelca por stdout.
      docker exec "$CONTAINER" pg_dump \
        --username "$PGUSER" --dbname "$PGDATABASE" \
        --format custom --compress 9 --no-owner --no-privileges > "$out"
    }

  [ -s "$out" ] || fail "El dump resulto vacio: ${out}"

  # Checksum: sin el, un restore no puede probarse la integridad (R-28).
  log "Calculando checksum"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$out" > "$meta"
  else
    shasum -a 256 "$out" > "$meta"
  fi

  log "Backup completado: ${out} ($(du -h "$out" | cut -f1))"

  # Retencion: nada de dumps antiguos mas alla de la ventana definida.
  log "Aplicando retencion de ${RETENTION_DAYS} dias en ${dest_dir}"
  find "$dest_dir" -name 'xmr_forecast_*.dump' -type f -mtime "+${RETENTION_DAYS}" -print -delete

  # Retencion y borrado de datos, documentados en docs/09.
  log "AVISO: este backup contiene datos de usuarios. Cifralo y restaurol"
  log "solo en entornos controlados (R-27, R-30)."
}

do_verify() {
  local dump="$1"
  [ -f "$dump" ] || fail "No existe el fichero: ${dump}"

  if [ -f "${dump}.sha256" ]; then
    log "Verificando checksum"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum -c "${dump}.sha256" || fail "Checksum incorrecto: el backup esta corrupto"
    else
      shasum -a 256 -c "${dump}.sha256" || fail "Checksum incorrecto: el backup esta corrupto"
    fi
  else
    log "AVISO: no hay fichero .sha256; se omite la verificacion de integridad"
  fi

  require_container
  log "Verificando la estructura del dump con pg_restore --list"
  docker exec -i "$CONTAINER" pg_restore --list < "$dump" > /dev/null \
    || fail "El dump no es legible por pg_restore"

  local tables
  tables=$(docker exec -i "$CONTAINER" pg_restore --list < "$dump" | grep -c 'TABLE DATA' || true)
  log "Dump valido. Tablas con datos: ${tables}"
}

do_restore() {
  local dump="$1"
  [ -f "$dump" ] || fail "No existe el fichero: ${dump}"
  require_container

  log "ATENCION: la restauracion REEMPLAZA los datos actuales de '${PGDATABASE}'."

  do_verify "$dump"

  # --clean --if-exists deja la base en un estado limpio antes de restaurar.
  log "Restaurando en '${PGDATABASE}'"
  docker exec -i "$CONTAINER" pg_restore \
    --username "$PGUSER" \
    --dbname "$PGDATABASE" \
    --clean \
    --if-exists \
    --no-owner \
    --no-privileges \
    < "$dump"

  log "Restauracion completada. Comprobando integridad referencial:"
  # Una restauracion que deja datos huerfanos no es una restauracion valida.
  # Se comprueban las relaciones que sostienen el dominio, no solo que la base
  # responde: un 'users' con recuento correcto no dice nada de las metricas de
  # una corrida que ya no existe.
  local orphans=0
  while IFS='|' read -r label total huerfanos; do
    [ -n "${total:-}" ] || continue
    log "  ${label}: ${total} filas, ${huerfanos} huerfanas"
    orphans=$(( orphans + ${huerfanos:-0} ))
  done <<EOF
$(docker exec "$CONTAINER" psql -U "$PGUSER" -d "$PGDATABASE" -t -A -F'|' -c "
  SELECT 'users',
         (SELECT count(*) FROM users),
         (SELECT count(*) FROM user_roles r WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = r.user_id));
  SELECT 'predictions -> model_versions',
         (SELECT count(*) FROM predictions),
         (SELECT count(*) FROM predictions p WHERE NOT EXISTS (SELECT 1 FROM model_versions v WHERE v.id = p.model_version_id));
  SELECT 'experiments -> dataset_versions',
         (SELECT count(*) FROM experiments),
         (SELECT count(*) FROM experiments e WHERE NOT EXISTS (SELECT 1 FROM dataset_versions d WHERE d.id = e.dataset_version_id));
  SELECT 'metrics -> experiment_runs',
         (SELECT count(*) FROM metrics),
         (SELECT count(*) FROM metrics m WHERE NOT EXISTS (SELECT 1 FROM experiment_runs r WHERE r.id = m.run_id));
  SELECT 'model_versions -> models',
         (SELECT count(*) FROM model_versions),
         (SELECT count(*) FROM model_versions v WHERE NOT EXISTS (SELECT 1 FROM models m WHERE m.id = v.model_id));
" 2>/dev/null)
EOF

  if [ "$orphans" -gt 0 ]; then
    fail "La restauracion dejo ${orphans} filas huerfanas: el dump es inconsistente"
  fi
  log "OK: sin filas huerfanas"
}

do_list() {
  local dest_dir="${1:-$BACKUP_ROOT}"
  [ -d "$dest_dir" ] || { log "No hay directorio de backups: ${dest_dir}"; return 0; }
  log "Backups en ${dest_dir}:"
  ls -lh "$dest_dir"/xmr_forecast_*.dump 2>/dev/null || log "  (ninguno)"
}

case "${1:-}" in
  backup)  do_backup  "${2:-}" ;;
  verify)  [ -n "${2:-}" ] || fail "Uso: $0 verify <fichero>"; do_verify "$2" ;;
  restore) [ -n "${2:-}" ] || fail "Uso: $0 restore <fichero>"; do_restore "$2" ;;
  list)    do_list "${2:-}" ;;
  *)       echo "Uso: $0 {backup|verify <f>|restore <f>|list}"; exit 1 ;;
esac