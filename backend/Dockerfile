# =====================================================================
# XMR-Forecast backend :: imagen multi-etapa
# R-36: la imagen de compilacion y la de ejecucion usan EXACTAMENTE la
#       misma version de Java 21 (LTS). No cambiar una sin la otra.
# =====================================================================

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Capa de dependencias: se reaprovecha mientras los pom no cambien.
#
# El pom raiz es un AGREGADOR (packaging=pom) y el modulo es backend/. Por eso
# la copia tiene que respetar esa estructura. La version anterior copiaba el pom
# del backend a /build/backend-pom.xml y ejecutaba `mvn dependency:go-offline`
# desde /build, donde no hay ningun pom: Maven abortaba con "there is no POM in
# this directory" y el `|| true` se tragaba el fallo. La capa no cacheaba nada
# y, peor, un fallo real de resolucion de dependencias tambien habria pasado
# desapercibido hasta el empaquetado final.
COPY pom.xml ./pom.xml
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -B -q -pl backend -am dependency:go-offline -DskipTests

# Codigo fuente y empaquetado real.
COPY backend/src ./backend/src
RUN set -eux; \
    mvn -B -pl backend -am clean package -DskipTests

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-jammy AS runtime

# Usuario sin privilegios: el proceso nunca corre como root.
RUN groupadd --system --gid 1001 xmr \
 && useradd --system --uid 1001 --gid xmr --create-home --shell /usr/sbin/nologin xmr

WORKDIR /app

# Certificados TLS montados en /certs (solo lectura, ver docker-compose).
RUN mkdir -p /certs && chown xmr:xmr /certs

COPY --from=build --chown=xmr:xmr /build/backend/target/xmr-forecast-backend.jar /app/app.jar

USER xmr
EXPOSE 8443

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8443 \
    SERVER_SSL_KEYSTORE=/certs/backend/keystore.p12

# Health check: wget de GNU coreutils (Ubuntu) contra HTTPS, validando la cadena
# de CA propia. No se usa --no-check-certificate: si el certificado no valida, el
# contenedor debe considerarse unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 -O /dev/null \
      --ca-certificate=/certs/ca/ca.crt \
      "https://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]