# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-atlassian:jar --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-atlassian/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx1g -Xms256m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]
