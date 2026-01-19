# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    git universal-ctags curl ca-certificates gnupg && \
    curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.31/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg && \
    chmod 644 /etc/apt/keyrings/kubernetes-apt-keyring.gpg && \
    echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.31/deb/ /' | tee /etc/apt/sources.list.d/kubernetes.list && \
    chmod 644 /etc/apt/sources.list.d/kubernetes.list && \
    apt-get update && \
    apt-get install -y kubectl && \
    rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir aider-chat

WORKDIR /opt/jervis/aider
COPY backend/service-aider/build/libs/*.jar jervis-aider.jar
ENV PORT=3100 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    PYTHONIOENCODING=utf-8
EXPOSE 3100
EXPOSE 3101
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:3101/health || exit 1
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/aider/jervis-aider.jar"]
