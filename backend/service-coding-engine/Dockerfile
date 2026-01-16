# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true GRADLE_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m" gradle :backend:service-coding-engine:jar --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    software-properties-common git curl ca-certificates gnupg && \
    add-apt-repository ppa:deadsnakes/ppa -y && \
    apt-get update && \
    apt-get install -y --no-install-recommends python3.12 python3.12-venv python3.12-dev && \
    rm -rf /var/lib/apt/lists/*

RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.31/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg && \
    chmod 644 /etc/apt/keyrings/kubernetes-apt-keyring.gpg && \
    echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.31/deb/ /' | tee /etc/apt/sources.list.d/kubernetes.list && \
    chmod 644 /etc/apt/sources.list.d/kubernetes.list && \
    apt-get update && \
    apt-get install -y docker-ce-cli kubectl && \
    rm -rf /var/lib/apt/lists/*

ENV VIRTUAL_ENV=/opt/venv
RUN python3.12 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir openhands-ai

WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-coding-engine/build/libs/*.jar app.jar
ENV SERVER_PORT=3200 \
    JAVA_OPTS="-Xmx2g -Xms512m" \
    DATA_ROOT_DIR=/opt/jervis/data \
    DOCKER_HOST=tcp://localhost:2375
EXPOSE 3200
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/.well-known/agent-card.json || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${DATA_ROOT_DIR} && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /opt/jervis/app.jar"]
