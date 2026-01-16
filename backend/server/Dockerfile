# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true GRADLE_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m" \
    gradle clean && \
    gradle -x test --no-daemon :backend:common-services:jar && \
    gradle -x test --no-daemon :backend:server:bootJar

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis

# Kopírování JAR aplikace
COPY --from=builder /app/backend/server/build/libs/*.jar app.jar

ENV SERVER_PORT=5500 JAVA_OPTS="-Xmx4g -Xms1g" DATA_ROOT_DIR=/opt/jervis/data WORK_DATA=/opt/jervis/work
EXPOSE 5500
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:5500/health || exit 1
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "WD=${WORK_DATA}; if [ -z \"$WD\" ]; then WD=$(printenv WORK-DATA || true); fi; if [ -z \"$WD\" ]; then WD=/opt/jervis/work; fi; mkdir -p ${DATA_ROOT_DIR} $WD && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -Djava.io.tmpdir=$WD -jar /opt/jervis/app.jar"]
