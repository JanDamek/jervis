# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-atlassian:jar --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/jervis/atlassian
COPY --from=builder /app/backend/service-atlassian/build/libs/*.jar jervis-atlassian.jar
ENV JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8084/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/atlassian/jervis-atlassian.jar --spring.config.additional-location=optional:file:/opt/jervis/atlassian/config/"]
