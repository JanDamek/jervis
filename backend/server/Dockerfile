# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/server

# Kopírování JAR aplikace (předpokládá se build hostitelem)
COPY backend/server/build/libs/*.jar jervis-server.jar

ENV JAVA_OPTS="-Xmx4g -Xms1g"
EXPOSE 5500
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:5500/health || exit 1
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/server/jervis-server.jar --spring.config.additional-location=optional:file:/opt/jervis/server/config/"]
