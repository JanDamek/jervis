# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-junie:jar --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git curl ca-certificates libarchive-tools && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/junie && \
    curl -fsSL https://github.com/jetbrains-junie/junie/releases/download/576.1/junie-eap-576.1-linux-amd64.zip -o /tmp/junie.zip && \
    bsdtar -x -f /tmp/junie.zip -C /opt/junie && \
    chmod +x /opt/junie/junie && \
    ln -s /opt/junie/junie /usr/local/bin/junie && \
    rm /tmp/junie.zip

WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-junie/build/libs/*.jar app.jar
ENV SERVER_PORT=3300 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    DATA_ROOT_DIR=/opt/jervis/data
EXPOSE 3300
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/.well-known/agent-card.json || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${DATA_ROOT_DIR} && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /opt/jervis/app.jar"]
