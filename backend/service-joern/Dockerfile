# --- Joern CLI Download Stage ---
FROM alpine:3.21 AS joern-downloader
RUN apk add --no-cache wget unzip
RUN mkdir -p /opt/joern && \
    wget -q "https://github.com/joernio/joern/releases/download/v4.0.439/joern-cli.zip" -O /tmp/joern.zip && \
    unzip -q /tmp/joern.zip -d /opt/joern && \
    chmod +x /opt/joern/joern-cli/joern && \
    rm /tmp/joern.zip

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=joern-downloader /opt/joern /opt/joern
ENV JOERN_HOME=/opt/joern
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"

WORKDIR /opt/jervis/joern
COPY backend/service-joern/build/libs/*.jar jervis-joern.jar

ENV JAVA_OPTS="-Xmx2g -Xms512m"
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8082/health || exit 1
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/joern/jervis-joern.jar"]
