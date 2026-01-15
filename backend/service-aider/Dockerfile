# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-aider:jar --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    git universal-ctags curl \
    && rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir aider-chat

WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-aider/build/libs/*.jar app.jar
ENV SERVER_PORT=3100 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    DATA_ROOT_DIR=/opt/jervis/data \
    PYTHONIOENCODING=utf-8
EXPOSE 3100
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/.well-known/agent-card.json || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${DATA_ROOT_DIR} && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /opt/jervis/app.jar"]
