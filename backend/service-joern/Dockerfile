# =============================================================================
# Joern K8s Job Image
# Runs as a K8s Job (not a persistent Deployment).
# Contains Joern CLI + Python3 for structured JSON output.
# No Kotlin JAR, no kRPC, no HTTP server.
# =============================================================================

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
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=joern-downloader /opt/joern /opt/joern
ENV JOERN_HOME=/opt/joern
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"

WORKDIR /opt/jervis
COPY backend/service-joern/entrypoint-joern-job.sh /opt/jervis/entrypoint-joern-job.sh
COPY backend/service-joern/entrypoint-joern-cpg-export.sh /opt/jervis/entrypoint-joern-cpg-export.sh
COPY backend/service-joern/cpg-export-query.sc /opt/jervis/cpg-export-query.sc
RUN chmod +x /opt/jervis/entrypoint-joern-job.sh /opt/jervis/entrypoint-joern-cpg-export.sh

ENV JAVA_OPTS="-Xmx2g -Xms512m"
# Default entrypoint for ad-hoc queries; CPG export uses command override
ENTRYPOINT ["/opt/jervis/entrypoint-joern-job.sh"]
