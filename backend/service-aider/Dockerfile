# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    git universal-ctags curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir aider-chat

WORKDIR /opt/jervis/aider
COPY backend/service-aider/build/libs/service-aider-*.jar jervis-aider.jar

ENV PORT=3100 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    PYTHONIOENCODING=utf-8
EXPOSE 3100

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/aider/jervis-aider.jar"]
