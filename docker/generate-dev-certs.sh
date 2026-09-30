#!/usr/bin/env bash
# =====================================================================
# XMR-Forecast :: generacion de certificados TLS de DESARROLLO
#
# QUE HACE Y QUE NO HACE
#   Genera una CA propia y certificados autofirmados para que HTTPS funcione
#   en local (R-33 exige HTTPS en todos los ambientes, desarrollo incluido).
#
#   NO es un certificado valido para produccion. Un certificado autofirmado
#   obliga a cada cliente a confiar en la CA instalada a mano, y en cuanto
#   sale del entorno de desarrollo el coste de gestion deja de ser aceptable.
#   En produccion los certificados los emite una CA publica y los secretos
#   viven fuera de git (R-27, R-33).
#
# USO
#   bash docker/generate-dev-certs.sh
#
# PORTABILIDAD
#   El script trabaja con rutas RELATIVAS y evita la sustitucion de procesos
#   por dos motivos, ambos reales:
#     1. En Git-for-Windows, `openssl` es un binario nativo (mingw64) que no
#        resuelve rutas al estilo MSYS (`/c/Users/...`).
#     2. Ese mismo binario no puede leer `/dev/fd/N`, que es donde aterriza la
#        salida de `<(...)`.
#   Con rutas relativas y ficheros reales, el mismo script corre en Linux,
#   macOS, WSL y Git Bash sin cambios.
#
# SALIDA (todo bajo docker/certs/, ignorado por git)
#   ca/ca.crt, ca/ca.key, ca/truststore.p12
#   backend/cert.pem, backend/key.pem, backend/keystore.p12
#   ml-service/cert.pem, ml-service/key.pem
#   mlflow/cert.pem, mlflow/key.pem
# =====================================================================
set -euo pipefail

# Git-for-Windows (MSYS2) convierte cualquier ARGUMENTO que empiece por "/" en
# una ruta de Windows: `-subj "/C=ES/..."` se convertiria en
# "C:/Program Files/Git/C=ES/..." y openssl abortaria con un error que no
# menciona el origen del problema. La conversion afecta al argumento, no al
# resto del script, asi que las rutas pueden seguir siendo relativas.
# En Linux y macOS estas variables se ignoran.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CERT_DIR="certs"
KEYSTORE_PASSWORD="${SERVER_SSL_KEYSTORE_PASSWORD:-changeit}"
KEY_ALIAS="${SERVER_SSL_KEY_ALIAS:-backend}"
DAYS="${CERT_DAYS:-825}"
# include-subdomains: los navegadores rechazan los certificados sin este flag.
EXT="subjectAltName=DNS:localhost,DNS:backend,DNS:ml-service,DNS:mlflow,IP:127.0.0.1"
EXT_FILE="openssl-ext.cnf"

log()  { printf '\033[0;36m[certs]\033[0m %s\n' "$1"; }
fail() { printf '\033[0;31m[certs] ERROR:\033[0m %s\n' "$1" >&2; exit 1; }

command -v openssl >/dev/null 2>&1 || fail "Se requiere openssl en el PATH"

# Sobrescribir es una decision consciente: regenerar la CA invalida la
# confianza ya instalada en los navegadores. Se exige confirmacion explicita.
if [ -f "${CERT_DIR}/ca/ca.crt" ] && [ "${FORCE:-0}" != "1" ]; then
  log "Ya existe una CA en ${CERT_DIR}/ca. Usa FORCE=1 para regenerarla."
  log "(Regenerar exige volver a importar la CA en el navegador.)"
  exit 0
fi

mkdir -p "${CERT_DIR}/ca" "${CERT_DIR}/backend" "${CERT_DIR}/ml-service" "${CERT_DIR}/mlflow" "${CERT_DIR}/frontend"

log "Generando la CA de desarrollo (autoridad certificadora local)"
openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS" -nodes \
  -keyout "${CERT_DIR}/ca/ca.key" \
  -out    "${CERT_DIR}/ca/ca.crt" \
  -subj   "/C=ES/O=AyLZz Software Solutions/OU=XMR-Forecast Development/CN=XMR-Forecast Dev CA" \
  >/dev/null 2>&1

# Fichero real, no `<(...)`: los binarios nativos no leen /dev/fd.
cat > "$EXT_FILE" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
${EXT}
EOF

# issue <servicio> <directorio>
issue() {
  local service="$1" dir="${CERT_DIR}/$2"
  log "Emitiendo certificado para '${service}'"
  openssl req -newkey rsa:2048 -sha256 -nodes \
    -keyout "${dir}/key.pem" \
    -out    "${dir}/cert.csr" \
    -subj   "/C=ES/O=AyLZz Software Solutions/OU=XMR-Forecast Development/CN=${service}" \
    >/dev/null 2>&1

  # Autoridad de certificacion propia: sin ella el certificado no cadena.
  openssl x509 -req -in "${dir}/cert.csr" -sha256 -days "$DAYS" \
    -CA "${CERT_DIR}/ca/ca.crt" -CAkey "${CERT_DIR}/ca/ca.key" -CAcreateserial \
    -extfile "$EXT_FILE" \
    -out "${dir}/cert.pem" >/dev/null 2>&1

  rm -f "${dir}/cert.csr"
  # La clave nunca puede quedar legible para otros.
  chmod 600 "${dir}/key.pem"
  chmod 644 "${dir}/cert.pem"
}

issue "backend"    "backend"
issue "ml-service" "ml-service"
issue "mlflow"     "mlflow"
issue "frontend"   "frontend"
rm -f "$EXT_FILE"

log "Empaquetando el keystore PKCS12 del backend"
# Se usa openssl y no keytool a proposito: elimina la dependencia de un JDK
# concreto y evita que el `keytool` que aparezca primero en el PATH sea el de una
# version de Java distinta de la que exige el proyecto (R-36).
# `-certfile` incluye la CA en la cadena: sin ella el navegador ve un emisor
# desconocido aunque el certificado sea correcto.
openssl pkcs12 -export \
  -inkey "${CERT_DIR}/backend/key.pem" \
  -in    "${CERT_DIR}/backend/cert.pem" \
  -certfile "${CERT_DIR}/ca/ca.crt" \
  -name  "$KEY_ALIAS" \
  -out   "${CERT_DIR}/backend/keystore.p12" \
  -passout "pass:${KEYSTORE_PASSWORD}" >/dev/null 2>&1

log "Empaquetando el truststore PKCS12 compartido"
# Se usa keytool y no `openssl pkcs12 -export -nokeys` a proposito: el PKCS12
# que genera OpenSSL 3 para un almacen solo-certificados lo lee Java como
# vacio (0 entradas) y Tomcat aborta con "the trustAnchors parameter must be
# non-empty". Verificado con OpenSSL 3.5.7 + keytool de JDK 21.
command -v keytool >/dev/null 2>&1 || fail "Se requiere keytool en el PATH para el truststore"
rm -f "${CERT_DIR}/ca/truststore.p12"
keytool -importcert -noprompt -trustcacerts \
  -alias "devca" \
  -file "${CERT_DIR}/ca/ca.crt" \
  -keystore "${CERT_DIR}/ca/truststore.p12" \
  -storetype PKCS12 \
  -storepass "${KEYSTORE_PASSWORD}" >/dev/null 2>&1 \
  || fail "No se pudo crear el truststore PKCS12"

rm -f "${CERT_DIR}/ca/ca.srl"

log "Verificando la cadena de confianza"
openssl verify -CAfile "${CERT_DIR}/ca/ca.crt" "${CERT_DIR}/backend/cert.pem" >/dev/null 2>&1 \
  || fail "La cadena de confianza del backend no valida"

# El fichero puede existir y estar truncado: el tamano es la unica comprobacion
# disponible sin depender de un keytool concreto.
[ -s "${CERT_DIR}/backend/keystore.p12" ] || fail "El keystore PKCS12 salio vacio"
[ -s "${CERT_DIR}/ca/truststore.p12" ] || fail "El truststore PKCS12 salio vacio"

log "OK. Certificados en ${SCRIPT_DIR}/${CERT_DIR}"
log ""
log "Para que el navegador confie (solo desarrollo):"
log "  Linux/macOS : sudo cp ${CERT_DIR}/ca/ca.crt /usr/local/share/ca-certificates/xmr-dev.crt && sudo update-ca-certificates"
log "  Windows     : Import-Certificate -FilePath '.\\${CERT_DIR//\//\\}\\ca\\ca.crt' -CertStoreLocation Cert:\\LocalMachine\\Root"
log "  Firefox     : Ajustes > Certificados > Importar > Authorities > ${CERT_DIR}/ca/ca.crt"
log ""
log "En produccion estos ficheros NO se usan: los emite una CA publica (R-33)."
